package cl.streambox.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.ui.PlayerView;

import okhttp3.OkHttpClient;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class MainActivity extends MainSelector {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        classicUi = UiStyle.beginStart(this);
        consumeReminderIntent(getIntent());
        // Android borra las alarmas si la app fue detenida: se reponen al abrirla.
        // Recordatorios: no hacen falta para el primer cuadro; se reponen 10 s después (0.5.87).
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) ReminderAlerts.rescheduleAll(this);
        }, 10_000L);
        setContentView(R.layout.activity_main);
        // La raíz ya pinta negro toda la pantalla: sin el fondo de la ventana no se dibuja dos
        // veces cada cuadro (lint Overdraw, auditoría 0.6.01).
        getWindow().setBackgroundDrawable(null);
        SettingsActivity.ensureDefaultPlaylistConfigured(this);
        repository = new PlaylistRepository(this);
        epgRepository = new EpgRepository(this);
        channelLogoCache = new ChannelLogoCache(this);
        LogoScales.load(this);
        playbackPreferences = new PlaybackPreferences(this);
        channelVisibility = new ChannelVisibility(this);
        resolverCatalogRepository = new ResolverCatalogRepository(this);
        resolverPreferences = new ResolverPreferences(this);
        PublishedPlaybackCatalogRepository.clearRetiredLocalSelections(this);
        publishedPlaybackCatalogRepository = new PublishedPlaybackCatalogRepository(this);
        publishedPlaybackCatalog = PublishedPlaybackCatalog.empty();
        publishedCatalogRefreshPending = true;
        // Install the process-local alias learning store before any resolver
        // can be used. It persists aliases only, never resolved URLs/tokens.
        new TvVooSourceHistory(this);
        // Deja disponible el token Premium cifrado para el resolutor de Highfly.
        HighflyPremiumCredentialStore.getInstance(this);
        reloadResolverRegistry();
        bindViews();
        // Si en 5 s la pantalla sigue viva, el estilo elegido arrancó bien (ver UiStyle).
        mainHandler.postDelayed(() -> UiStyle.startCompleted(this), 5_000L);
        registerBackCallback();
        enterImmersiveMode();
        createPlayer();
        refreshPublishedPlaybackCatalog();
        mainHandler.postDelayed(catalogRefreshTicker, CATALOG_REFRESH_INTERVAL_MS);
        if (BuildConfig.ENABLE_APP_UPDATES) {
            appUpdater = new AppUpdater(this, networkExecutor, mainHandler);
            mainHandler.postDelayed(appUpdater::checkForUpdates, UPDATE_CHECK_DELAY_MS);
        }
        updateClock.run();
    }

    void reloadResolverRegistry() {
        if (streamResolverRegistry != null) streamResolverRegistry.clearSensitiveState();
        try {
            streamResolverRegistry = new StreamResolverRegistry(
                    resolverCatalogRepository.load(),
                    resolverPreferences
            );
        } catch (Exception error) {
            Log.w(RESOURCE_HEALTH_TAG, "Catálogo de resolutores no cargó; quedan los integrados", error);
            // The two original exact-ID resolvers remain available even if a
            // local catalogue update was interrupted or became incompatible.
            streamResolverRegistry = new StreamResolverRegistry();
        }
    }

    void bindViews() {
        playerView = findViewById(R.id.player_view);
        channelOverlay = findViewById(R.id.channel_overlay);
        android.util.DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int panelWidth = OverlayPanelWidth.resolveWidthPx(
                displayMetrics.widthPixels,
                displayMetrics.density
        );
        android.view.LayoutInflater.from(this).inflate(
                classicUi ? R.layout.classic_osd : R.layout.osd_modern,
                (ViewGroup) channelOverlay, true);
        if (classicUi) {
            // Panel clásico: ancho de pantalla menos 32 dp por lado, centrado abajo.
            View panel = ((ViewGroup) channelOverlay).getChildAt(0);
            FrameLayout.LayoutParams panelParams = (FrameLayout.LayoutParams) panel.getLayoutParams();
            panelParams.width = panelWidth;
            panelParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            panelParams.leftMargin = 0;
            panelParams.rightMargin = 0;
            panel.setLayoutParams(panelParams);
        }
        loadingPanel = findViewById(R.id.loading_panel);
        loadingText = findViewById(R.id.loading_text);
        loadingKicker = findViewById(R.id.loading_kicker);
        playbackErrorOverlay = findViewById(R.id.playback_error_overlay);
        playbackErrorTitle = findViewById(R.id.playback_error_title);
        playbackErrorEyebrow = findViewById(R.id.playback_error_eyebrow);
        playbackErrorMessage = findViewById(R.id.playback_error_message);
        playbackErrorTried = findViewById(R.id.playback_error_tried);
        playbackErrorCode = findViewById(R.id.playback_error_code);
        playbackErrorClock = findViewById(R.id.playback_error_clock);
        playbackErrorDate = findViewById(R.id.playback_error_date);
        playbackErrorHint = findViewById(R.id.playback_error_hint);
        playbackErrorLogo = findViewById(R.id.playback_error_logo);
        playbackErrorRetry = findViewById(R.id.playback_error_retry);
        playbackErrorSecondary = findViewById(R.id.playback_error_secondary);
        noticeOverlay = findViewById(R.id.notice_overlay);
        noticeText = findViewById(R.id.notice_text);
        if (playbackErrorRetry != null) {
            SceneDialog.boldWhenFocused(playbackErrorRetry, playbackErrorSecondary);
        }
        clock = findViewById(R.id.clock);
        android.view.ViewStub detailStub = findViewById(R.id.programme_detail_stub);
        detailStub.setLayoutResource(classicUi
                ? R.layout.classic_programme_detail_overlay : R.layout.programme_detail_overlay);
        programmeDetailOverlay = detailStub.inflate();
        FrameLayout.LayoutParams detailParams = (FrameLayout.LayoutParams)
                programmeDetailOverlay.getLayoutParams();
        detailParams.width = panelWidth;
        detailParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        // Clásico: se apoya sobre el panel del OSD; moderno: ocupa el lugar de su bloque.
        detailParams.bottomMargin = dpToPx(classicUi ? 118 : 46);
        programmeDetailOverlay.setLayoutParams(detailParams);
        detailLive = findViewById(R.id.detail_live);
        detailLabel = findViewById(R.id.detail_label);
        detailTitle = findViewById(R.id.detail_title);
        detailMeta = findViewById(R.id.detail_meta);
        detailProgress = findViewById(R.id.detail_progress);
        detailDescription = findViewById(R.id.detail_description);
        detailSide = findViewById(R.id.detail_side);
        detailNextLabel = findViewById(R.id.detail_next_label);
        detailNextTitle = findViewById(R.id.detail_next_title);
        detailAfterLabel = findViewById(R.id.detail_after_label);
        detailAfterTitle = findViewById(R.id.detail_after_title);
        guideView = findViewById(classicUi ? R.id.guide_overlay_classic : R.id.guide_overlay);
        guideSurface = (GuideSurface) guideView;
        sourceSelectorOverlay = findViewById(R.id.source_selector_overlay);
        sourceSelectorTitle = findViewById(R.id.source_selector_title);
        sourceSelectorChannel = findViewById(R.id.source_selector_channel);
        sourceSelectorStatus = findViewById(R.id.source_selector_status);
        sourceSelectorOptions = findViewById(R.id.source_selector_options);
        sourceSelectorCloseButton = findViewById(R.id.source_selector_close);
        qualityUpgradeOverlay = findViewById(R.id.quality_upgrade_overlay);
        qualityUpgradeTitle = findViewById(R.id.quality_upgrade_title);
        qualityUpgradeMessage = findViewById(R.id.quality_upgrade_message);
        qualityUpgradeSwitch = findViewById(R.id.quality_upgrade_switch);
        qualityUpgradeDismiss = findViewById(R.id.quality_upgrade_dismiss);
        SceneDialog.boldWhenFocused(qualityUpgradeSwitch, qualityUpgradeDismiss);
        qualityUpgradeTimer = findViewById(R.id.quality_upgrade_timer);
        qualityUpgradeSwitch.setOnClickListener(view -> acceptQualityUpgrade());
        qualityUpgradeDismiss.setOnClickListener(view -> hideQualityUpgrade());
        sourceSelectorCloseButton.setOnFocusChangeListener((view, focused) -> {
            if (focused) sourceSelectorCloseFocused = true;
        });
        sourceSelectorCloseButton.setOnClickListener(view -> closePlaybackSourceSelector());
        channelLogo = findViewById(R.id.channel_logo);
        channelLogoFallback = findViewById(R.id.channel_logo_fallback);
        channelLogoFrame = findViewById(R.id.channel_logo_frame);
        logoSizeAdjust = findViewById(R.id.logo_size_adjust);
        audioSyncOverlay = findViewById(R.id.audio_sync_overlay);
        audioSyncValueView = findViewById(R.id.audio_sync_value);
        logoSizeValue = findViewById(R.id.logo_size_value);
        if (channelLogoFrame != null && logoSizeAdjust != null) {
            View.OnLayoutChangeListener reposition = (view, l, t, r, b, ol, ot, or, ob) -> {
                if (logoSizeAdjusting) positionLogoSizeAdjust();
            };
            channelLogoFrame.addOnLayoutChangeListener(reposition);
            logoSizeAdjust.addOnLayoutChangeListener(reposition);
        }
        channelNumber = findViewById(R.id.channel_number);
        channelName = findViewById(R.id.channel_name);
        contentTitle = findViewById(R.id.content_title);
        programmeTime = findViewById(R.id.programme_time);
        liveProgress = findViewById(R.id.live_progress);
        osdHero = findViewById(R.id.osd_hero);
        osdDescription = findViewById(R.id.osd_description);
        osdNext = findViewById(R.id.osd_next);
        osdEyebrow = findViewById(R.id.osd_eyebrow);
        osdClockTime = findViewById(R.id.osd_clock_time);
        osdClockDate = findViewById(R.id.osd_clock_date);
        loadingProgress = findViewById(R.id.loading_progress);
        videoInfo = findViewById(R.id.video_info);
        codecInfo = findViewById(R.id.codec_info);
        statusDot = findViewById(R.id.status_dot);
        streamStatus = findViewById(R.id.stream_status);
        // Al final: el estilo clásico toca vistas del selector que se buscan más arriba.
        if (classicUi) applyClassicChrome();
        else applyMinimalSelectorShade();
    }


    void refreshPlaylists(List<PlaylistSource> configuredSources) {
        List<PlaylistSource> sources = configuredSources == null
                ? Collections.emptyList()
                : new ArrayList<>(configuredSources);
        String sourceSignature = playlistSourceSignature(sources);
        if (sources.isEmpty() && !hasPublishedProviderChannels()) {
            if (publishedCatalogRefreshPending) {
                showLoadingState(getString(R.string.loading_playlist));
            } else if (!settingsOpen) {
                openSettings();
            }
            return;
        }

        boolean sourceChanged = !sourceSignature.equals(loadedPlaylistSignature);
        boolean keepCurrentUi = !sourceChanged && !channels.isEmpty();
        // A source refresh can publish cache and network snapshots in several
        // callbacks. Keep the playback request alive while those snapshots
        // describe the same configured source; applyPlaylists will still
        // replace it if the selected channel disappears or its request
        // details change.
        boolean preserveCurrentPlayback = !sourceChanged && !channels.isEmpty();
        boolean resetPlayback = sourceChanged || channels.isEmpty();
        int generation = ++playlistGeneration;
        epgRequests.clear();
        resetEpgStaging();
        if (!preserveCurrentPlayback) {
            playbackGeneration++;
            cancelPlaybackResolution();
        }
        if (resetPlayback) {
            boolean discardProviderMedia = playbackChannel != null
                    && streamResolverRegistry.find(playbackChannel) != null;
            if (!discardProviderMedia && currentPlaybackSource != null) {
                discardProviderMedia = currentPlaybackSource.isDynamicallyResolved();
            }
            playbackChannel = null;
            discardCurrentPlaybackSource();
            if (discardProviderMedia && player != null) {
                player.stop();
                player.clearMediaItems();
            }
        }
        loadFailed = false;
        hidePlaybackErrorScreen();
        if (!keepCurrentUi) {
            showLoadingState(getString(R.string.loading_playlist));
        }
        if (sourceChanged) {
            channels.clear();
            channelIndex = 0;
            playlistsBySource.clear();
            epgDataByUrl.clear();
            activeEpgUrls.clear();
            epgData = EpgData.empty();
            epgMergeInputSignature = "";
            epgMergeGeneration++;
            mainHandler.removeCallbacks(updateProgramme);
        }
        if (!keepCurrentUi) {
            hideOverlay.run();
        }

        final boolean existingPlaylist = !channels.isEmpty();
        final Map<Integer, Playlist> visiblePlaylists = new LinkedHashMap<>(playlistsBySource);
        if (!existingPlaylist) {
            startupSelectionPending = true;
            startupPreferredFromLastChannel = AppStrings.isBlank(pendingReminderChannelIdentity);
            startupPreferredChannelIdentity = startupPreferredFromLastChannel
                    ? readLastChannelIdentity()
                    : pendingReminderChannelIdentity;
        }

        final boolean networkAvailable = isNetworkAvailable();
        PlaylistRefreshState refresh = new PlaylistRefreshState(
                generation,
                sourceSignature,
                visiblePlaylists,
                sources
        );
        // Provider rows come only from the freshly fetched website document.
        // Apply them immediately while configured M3U sources load.
        if (hasPublishedProviderChannels()) {
            applyPlaylists(
                    visiblePlaylists,
                    sourceSignature,
                    generation,
                    false
            );
        }
        if (!networkAvailable) {
            refresh.pendingNetwork = 0;
        }

        // Disk reads are deliberately short tasks. They report independently
        // and never wait for a network future, so a slow cache entry cannot
        // hold the executor while a remote source is being downloaded.
        for (PlaylistSource source : sources) {
            resourceCacheExecutor.submit(() -> {
                Playlist cached = null;
                try {
                    cached = repository.loadCached(source.getUrl());
                } catch (Exception error) {
                    Log.w(RESOURCE_HEALTH_TAG, "Lista en caché ilegible", error);
                    // The corresponding network result remains authoritative.
                }
                Playlist cachedResult = cached;
                mainHandler.post(() -> {
                    if (!isCurrentPlaylistRefresh(refresh)) return;
                    refresh.pendingCacheReads = Math.max(0, refresh.pendingCacheReads - 1);
                    int position = source.getPosition();
                    if (cachedResult != null && !refresh.completedNetworkPositions.contains(position)) {
                        refresh.latest.put(position, cachedResult);
                        publishPlaylistRefresh(refresh, false);
                    } else {
                        finishPlaylistRefreshIfReady(refresh);
                    }
                });
            });
        }

        if (networkAvailable) {
            for (PlaylistSource source : sources) {
                refresh.pendingNetwork++;
                networkExecutor.submit(() -> {
                    PlaylistNetworkResult result;
                    try {
                        PlaylistRepository.LoadResult loadResult =
                                repository.downloadIfChanged(source.getUrl());
                        result = PlaylistNetworkResult.success(source, loadResult);
                    } catch (Exception error) {
                        result = PlaylistNetworkResult.failure(source, error);
                    }
                    PlaylistNetworkResult completed = result;
                    mainHandler.post(() -> applyPlaylistNetworkResult(refresh, completed));
                });
            }
        }

        finishPlaylistRefreshIfReady(refresh);
    }

    boolean isCurrentPlaylistRefresh(PlaylistRefreshState refresh) {
        return refresh != null
                && refresh.generation == playlistGeneration
                && !isFinishing();
    }

    void applyPlaylistNetworkResult(
            PlaylistRefreshState refresh,
            PlaylistNetworkResult result
    ) {
        if (!isCurrentPlaylistRefresh(refresh) || result == null) return;
        int position = result.source.getPosition();
        if (!refresh.completedNetworkPositions.add(position)) return;
        refresh.pendingNetwork = Math.max(0, refresh.pendingNetwork - 1);
        if (result.playlist != null) {
            // Igual a la que ya se mostró desde el disco (0.6.17): no se rehace la lista de
            // canales en el hilo principal (con ~4.400 canales era trabajo visible al abrir).
            boolean alreadyShown = !result.changed && refresh.latest.containsKey(position);
            refresh.latest.put(position, result.playlist);
            if (alreadyShown) {
                finishPlaylistRefreshIfReady(refresh);
            } else {
                publishPlaylistRefresh(refresh, result.changed);
            }
        } else {
            if (refresh.firstError == null) refresh.firstError = result.error;
            finishPlaylistRefreshIfReady(refresh);
        }
    }

    void publishPlaylistRefresh(PlaylistRefreshState refresh, boolean contentChanged) {
        if (!isCurrentPlaylistRefresh(refresh) || refresh.latest.isEmpty()) {
            finishPlaylistRefreshIfReady(refresh);
            return;
        }
        if (waitsForListOne(refresh)) {
            // 0.6.23: al arrancar, Lista 2 no se muestra (ni reproduce) antes que Lista 1.
            finishPlaylistRefreshIfReady(refresh);
            return;
        }
        Map<Integer, Playlist> snapshot = new LinkedHashMap<>(refresh.latest);
        applyPlaylists(snapshot, refresh.sourceSignature, refresh.generation, contentChanged);
        loadEpgForPlaylists(snapshot, refresh.generation);
        finishPlaylistRefreshIfReady(refresh);
    }

    /** Arranque sin canales, Lista 1 configurada y todavía sin llegar (y la recarga sigue). */
    boolean waitsForListOne(PlaylistRefreshState refresh) {
        if (!channels.isEmpty() || refresh.isComplete()
                || refresh.latest.containsKey(LIST_ONE_SOURCE)) return false;
        for (PlaylistSource source : getPlaylistSources()) {
            if (source.getPosition() == LIST_ONE_SOURCE) return true;
        }
        return false;
    }

    void finishPlaylistRefreshIfReady(PlaylistRefreshState refresh) {
        if (!isCurrentPlaylistRefresh(refresh) || !refresh.isComplete()) return;
        if (channels.isEmpty() && !refresh.latest.isEmpty() && !isWaitingForPublishedCatalog()
                && !refresh.latest.containsKey(LIST_ONE_SOURCE)) {
            // Lista 1 falló: se muestra lo que llegó (p. ej. solo Lista 2) en vez de nada.
            Map<Integer, Playlist> snapshot = new LinkedHashMap<>(refresh.latest);
            applyPlaylists(snapshot, refresh.sourceSignature, refresh.generation, true);
            loadEpgForPlaylists(snapshot, refresh.generation);
        }
        // La lista ya llegó, pero espera al catálogo: la carga sigue en pantalla.
        if (isWaitingForPublishedCatalog() && !refresh.latest.isEmpty()) return;
        if (!refresh.latest.isEmpty()) {
            hidePlaylistLoadingIfPlaybackPending();
        } else if (!channels.isEmpty()) {
            hidePlaylistLoadingIfPlaybackPending();
        } else {
            showPlaylistError(shortMessage(refresh.firstError));
        }
        startupSelectionPending = false;
    }

    String readLastChannelIdentity() {
        return getSharedPreferences("playback_state", MODE_PRIVATE)
                .getString("last_channel", "");
    }



    /**
     * Guía por etapas (0.6.23, pedido del usuario): primero la de Lista 1 (caché y descarga);
     * la de Lista 2 recién cuando la de Lista 1 terminó. Si Lista 2 está apagada, su lista no
     * se carga y su guía (epg-2.xml) nunca se pide.
     */
    void loadEpgForPlaylists(Map<Integer, Playlist> playlists, int generation) {
        boolean listOneConfigured = false;
        for (PlaylistSource source : getPlaylistSources()) {
            if (source.getPosition() == LIST_ONE_SOURCE) listOneConfigured = true;
        }
        for (Map.Entry<Integer, Playlist> entry : orderedPlaylistEntries(playlists)) {
            Playlist playlist = entry.getValue();
            if (playlist == null) continue;
            boolean listOne = entry.getKey() == LIST_ONE_SOURCE;
            for (URI epgUri : playlist.getEpgUris()) {
                String url = epgUri.toString();
                if (!epgRequests.add(url)) continue;
                if (listOne) {
                    listOneEpgInFlight++;
                    submitEpgLoad(epgUri, generation, true);
                } else if (listOneEpgReady || !listOneConfigured) {
                    submitEpgLoad(epgUri, generation, false);
                } else {
                    deferredEpgUris.add(epgUri);
                    Log.i(PlaybackStartupAnalytics.LOG_TAG, "EPG en espera de Lista 1: " + url);
                }
            }
        }
        // Lista 1 ya pidió su guía antes (o no tiene): si no queda nada en curso, sigue Lista 2.
        if (playlists.containsKey(LIST_ONE_SOURCE) && listOneEpgInFlight == 0) {
            releaseDeferredEpg(generation);
        }
    }

    void submitEpgLoad(URI epgUri, int generation, boolean listOne) {
        if (listOne) epgRequestedAtMillis = android.os.SystemClock.elapsedRealtime();
        Log.i(PlaybackStartupAnalytics.LOG_TAG, "EPG pedida (" + (listOne ? "Lista 1" : "otra lista")
                + "): " + epgUri);
        epgExecutor.submit(() -> {
            EpgData local = null;
            try {
                local = epgRepository.loadCached(epgUri);
                if (local != null) {
                    EpgData cachedData = local;
                    mainHandler.post(() -> applyEpgData(cachedData, epgUri, generation));
                }
            } catch (Exception error) {
                Log.w(RESOURCE_HEALTH_TAG, "EPG en caché ilegible", error);
                // La reproducción continúa usando el grupo del canal como respaldo.
            }
            if (!isNetworkAvailable()) {
                if (listOne) mainHandler.post(() -> finishListOneEpg(generation));
                return;
            }

            EpgData baseline = local;
            epgExecutor.submit(() -> {
                try {
                    // 0.6.26: sin cambios y ya mostrada, la guía no se vuelve a procesar.
                    EpgRepository.LoadResult result =
                            epgRepository.downloadIfChanged(epgUri, baseline == null);
                    if ((result.isChanged() || baseline == null) && result.getData() != null) {
                        mainHandler.post(() -> applyEpgData(
                                result.getData(),
                                epgUri,
                                generation
                        ));
                    }
                } catch (Exception error) {
                    Log.w(RESOURCE_HEALTH_TAG, "EPG no se pudo actualizar", error);
                    // La programación cacheada permanece visible.
                } finally {
                    // Después de aplicar su guía (el post anterior va primero en la cola).
                    if (listOne) mainHandler.post(() -> finishListOneEpg(generation));
                }
            });
        });
    }

    void finishListOneEpg(int generation) {
        if (generation != playlistGeneration) return;
        listOneEpgInFlight = Math.max(0, listOneEpgInFlight - 1);
        if (listOneEpgInFlight == 0) releaseDeferredEpg(generation);
    }

    void releaseDeferredEpg(int generation) {
        if (listOneEpgReady && deferredEpgUris.isEmpty()) return;
        listOneEpgReady = true;
        Log.i(PlaybackStartupAnalytics.LOG_TAG, "EPG de Lista 1 lista; siguen " + deferredEpgUris.size()
                + " guías en espera");
        List<URI> pending = new ArrayList<>(deferredEpgUris);
        deferredEpgUris.clear();
        for (URI uri : pending) submitEpgLoad(uri, generation, false);
    }

    void resetEpgStaging() {
        listOneEpgReady = false;
        listOneEpgInFlight = 0;
        deferredEpgUris.clear();
    }

    /** Primer canal de Lista 1 en el orden visible; 0 si Lista 1 no está cargada. */
    int firstListOneChannelIndex() {
        for (int index = 0; index < channels.size(); index++) {
            if (listOneIdentities.contains(PlaybackPreferences.channelIdentity(channels.get(index)))) {
                return index;
            }
        }
        return 0;
    }

    static List<Map.Entry<Integer, Playlist>> orderedPlaylistEntries(
            Map<Integer, Playlist> playlists
    ) {
        List<Map.Entry<Integer, Playlist>> entries = new ArrayList<>();
        if (playlists != null) entries.addAll(playlists.entrySet());
        Collections.sort(entries, (left, right) ->
                Integer.compare(left.getKey(), right.getKey()));
        return entries;
    }

    void applyEpgData(EpgData data, URI epgUri, int generation) {
        if (generation != playlistGeneration || isFinishing()) return;
        String expectedUrl = epgUri == null ? "" : epgUri.toString();
        if (!activeEpgUrls.contains(expectedUrl)) return;
        epgDataByUrl.put(
                expectedUrl,
                data == null ? EpgData.empty() : data
        );
        scheduleEpgMerge(generation);
    }

    /**
     * Merges immutable XMLTV snapshots off the main thread. A guide can have
     * tens of thousands of programmes; rebuilding and sorting that index for
     * every source callback would otherwise compete with channel startup.
     */
    void scheduleEpgMerge(int generation) {
        String inputSignature = EpgData.mergeSignature(epgDataByUrl);
        if (inputSignature.equals(epgMergeInputSignature)) return;
        epgMergeInputSignature = inputSignature;
        long mergeGeneration = ++epgMergeGeneration;
        List<EpgData> snapshot = Collections.unmodifiableList(
                new ArrayList<>(epgDataByUrl.values())
        );
        epgMergeExecutor.submit(() -> {
            EpgData merged = EpgData.merge(snapshot);
            mainHandler.post(() -> {
                if (generation != playlistGeneration
                        || mergeGeneration != epgMergeGeneration
                        || isFinishing()) return;
                Log.i(PlaybackStartupAnalytics.LOG_TAG, "EPG visible: " + merged.getProgrammeCount()
                        + " programas, " + (android.os.SystemClock.elapsedRealtime() - epgRequestedAtMillis)
                        + " ms desde el pedido");
                epgData = merged;
                epgUpdatedAtMillis = System.currentTimeMillis();
                mainHandler.removeCallbacks(updateProgramme);
                updateProgramme.run();
            });
        });
    }

    void applyPlaylists(
            Map<Integer, Playlist> playlists,
            String sourceSignature,
            int generation,
            boolean contentChanged
    ) {
        if (generation != playlistGeneration || isFinishing()) return;

        boolean hadChannels = !channels.isEmpty();
        Channel previousChannel = hadChannels
                ? channels.get(Math.max(0, Math.min(channelIndex, channels.size() - 1)))
                : null;
        String previousIdentity = previousChannel == null
                ? ""
                : PlaybackPreferences.channelIdentity(previousChannel);
        URI previousStreamUri = previousChannel == null
                ? null
                : previousChannel.getStreamUri();

        playlistsBySource.clear();
        if (playlists != null) playlistsBySource.putAll(playlists);
        if (isWaitingForPublishedCatalog()) {
            loadedPlaylistSignature = sourceSignature;
            // La guía se sigue descargando mientras tanto: sus fuentes ya quedan
            // registradas para no descartar lo que llegue antes que el catálogo.
            for (Map.Entry<Integer, Playlist> entry : orderedPlaylistEntries(playlistsBySource)) {
                Playlist playlist = entry.getValue();
                if (playlist == null) continue;
                for (URI epgUri : playlist.getEpgUris()) activeEpgUrls.add(epgUri.toString());
            }
            mainHandler.removeCallbacks(publishedCatalogWaitTimeout);
            mainHandler.postDelayed(publishedCatalogWaitTimeout, PUBLISHED_CATALOG_WAIT_MS);
            return;
        }
        List<Channel> sourceChannels = buildOrderedChannelList(playlistsBySource);
        resolverChannelCounts = streamResolverRegistry.countChannels(sourceChannels);
        List<Channel> enabledChannels = new ArrayList<>();
        long visibilityNow = System.currentTimeMillis();
        java.util.Set<String> presentKeys = new java.util.HashSet<>();
        for (Channel candidate : sourceChannels) {
            String rowKey = PublishedPlaybackCatalog.editorRowKey(candidate);
            presentKeys.add(rowKey);
            // Ocultos en esta TV o enviados a la papelera desde la TV (0.5.90).
            if (channelVisibility.isHidden(rowKey, visibilityNow)) continue;
            if (streamResolverRegistry.isChannelEnabled(candidate)) {
                enabledChannels.add(candidate);
            }
        }
        if (!isWaitingForPublishedCatalog()) channelVisibility.prune(presentKeys, visibilityNow);

        channels.clear();
        channels.addAll(enabledChannels);
        listOneIdentities.clear();
        Playlist listOne = playlistsBySource.get(LIST_ONE_SOURCE);
        if (listOne != null) {
            for (Channel candidate : listOne.getChannels()) {
                listOneIdentities.add(PlaybackPreferences.channelIdentity(candidate));
            }
        }
        if (channels.isEmpty()) {
            showPlaylistError(getString(R.string.empty_playlist));
            return;
        }

        int nextChannelIndex = hadChannels
                ? PlaybackPreferences.findChannelIndex(
                        channels,
                        previousIdentity,
                        channelIndex
                )
                : firstListOneChannelIndex();
        if (startupSelectionPending) {
            if (AppStrings.isBlank(startupPreferredChannelIdentity)) {
                startupSelectionPending = false;
            } else if (startupPreferredFromLastChannel && listOne != null
                    && !listOneIdentities.contains(startupPreferredChannelIdentity)) {
                // 0.6.23: el último canal guardado no es de Lista 1 (p. ej. uno de Lista 2
                // guardado por una versión anterior): se parte en el primero de Lista 1.
                if (!hadChannels || !hasEstablishedPlayback()) {
                    nextChannelIndex = firstListOneChannelIndex();
                }
                startupSelectionPending = false;
            } else if (startupPreferredFromLastChannel && listOne == null) {
                // Lista 1 aún no llega: no se elige un canal de Lista 2 mientras tanto.
                if (!hadChannels) nextChannelIndex = 0;
            } else {
                int preferredIndex = findChannelIndexByIdentity(
                        channels,
                        startupPreferredChannelIdentity
                );
                if (preferredIndex >= 0) {
                    boolean preferredIsCurrent = hadChannels
                            && startupPreferredChannelIdentity.equals(previousIdentity);
                    if (!preferredIsCurrent && (!hadChannels || !hasEstablishedPlayback())) {
                        nextChannelIndex = preferredIndex;
                    }
                    // Once the preferred channel is visible, preserve the
                    // user's current channel if it is already healthy. This
                    // avoids a refresh interrupting an established stream.
                    startupSelectionPending = false;
                }
            }
        }
        channelIndex = nextChannelIndex;
        loadedPlaylistSignature = sourceSignature;

        LinkedHashSet<String> nextEpgUrls = new LinkedHashSet<>();
        for (Map.Entry<Integer, Playlist> entry : orderedPlaylistEntries(playlistsBySource)) {
            Playlist playlist = entry.getValue();
            if (playlist != null) {
                for (URI epgUri : playlist.getEpgUris()) {
                    nextEpgUrls.add(epgUri.toString());
                }
            }
        }
        boolean epgSourcesChanged = !nextEpgUrls.equals(activeEpgUrls);
        activeEpgUrls.clear();
        activeEpgUrls.addAll(nextEpgUrls);
        epgDataByUrl.keySet().retainAll(activeEpgUrls);
        scheduleEpgMerge(generation);
        if (epgSourcesChanged) {
            mainHandler.removeCallbacks(updateProgramme);
        }

        Channel selectedChannel = channels.get(channelIndex);
        boolean sameChannel = previousChannel != null
                && previousIdentity.equals(PlaybackPreferences.channelIdentity(selectedChannel));
        boolean requestHeadersChanged = sameChannel
                && !ChannelRequestHeaders.from(previousChannel).equals(
                ChannelRequestHeaders.from(selectedChannel)
        );
        boolean streamChanged = sameChannel && (
                (previousStreamUri != null
                        && !previousStreamUri.equals(selectedChannel.getStreamUri()))
                        || requestHeadersChanged
        );
        StreamResolver resolver = streamResolverRegistry.find(selectedChannel);
        boolean resolutionInFlightForChannel = sameChannel
                && playbackResolutionTask != null
                && playbackChannel != null
                && previousIdentity.equals(PlaybackPreferences.channelIdentity(playbackChannel));
        boolean preserveResolvedPlayback = sameChannel
                && !requestHeadersChanged
                && currentPlaybackSource != null
                && currentPlaybackSource.isDynamicallyResolved()
                && player != null
                && player.getCurrentMediaItem() != null
                && player.getPlayerError() == null
                && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlaybackState() != Player.STATE_ENDED;
        boolean resolverNeedsResolution = resolver != null
                && currentPlaybackSource == null
                && !resolutionInFlightForChannel;

        if (!hadChannels
                || !sameChannel
                || (streamChanged && !preserveResolvedPlayback
                        && !(resolutionInFlightForChannel && !requestHeadersChanged))
                || resolverNeedsResolution) {
            playChannel(channelIndex, contentChanged);
        } else {
            // The source may have been resolved for the previous Channel
            // object. Keep the fresh in-memory source, but associate it with
            // the current playlist object for future retries.
            // An in-flight resolver checks the original Channel object in
            // isCurrentPlayback(); replacing it here would make its result
            // stale even though the channel identity is unchanged.
            if (!resolutionInFlightForChannel) playbackChannel = selectedChannel;
            int displayNumber = publishedPlaybackCatalog.numberFor(
                    selectedChannel,
                    channelIndex + 1
            );
            bindOsdChannel(displayNumber, selectedChannel);
            updateProgrammeInfo();
            loadChannelLogo(selectedChannel, contentChanged);
            if (player != null && player.getPlaybackState() == Player.STATE_READY) hideLoadingState();
        }
        applyPendingReminderChannel();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeReminderIntent(intent);
        applyPendingReminderChannel();
    }

    void consumeReminderIntent(Intent intent) {
        if (intent == null) return;
        String identity = intent.getStringExtra(ReminderAlerts.EXTRA_OPEN_CHANNEL_IDENTITY);
        if (!AppStrings.isBlank(identity)) {
            pendingReminderChannelIdentity = identity;
            intent.removeExtra(ReminderAlerts.EXTRA_OPEN_CHANNEL_IDENTITY);
        }
    }

    /** «Ver» de un recordatorio: cambia al canal apenas la lista está cargada. */
    void applyPendingReminderChannel() {
        if (AppStrings.isBlank(pendingReminderChannelIdentity) || channels.isEmpty()) return;
        int index = findChannelIndexByIdentity(channels, pendingReminderChannelIdentity);
        pendingReminderChannelIdentity = "";
        if (index < 0) return;
        closeGuide();
        // Si ya es el canal actual, se deja como está (puede estar cargando).
        if (index != channelIndex) playChannel(index);
        showOverlay(false);
    }


    boolean hasEstablishedPlayback() {
        return player != null
                && player.getCurrentMediaItem() != null
                && player.getPlaybackState() == Player.STATE_READY;
    }

    /** Rehace la lista con las listas ya descargadas (canal oculto o vuelto a mostrar). */
    void reapplyChannelList() {
        if (playlistsBySource.isEmpty() || isFinishing()) return;
        applyPlaylists(new java.util.LinkedHashMap<>(playlistsBySource), loadedPlaylistSignature,
                playlistGeneration, true);
    }

    List<Channel> buildOrderedChannelList(Map<Integer, Playlist> playlists) {
        List<Channel> result = new ArrayList<>();
        for (Map.Entry<Integer, Playlist> entry : orderedPlaylistEntries(playlists)) {
            Playlist playlist = entry.getValue();
            if (playlist != null) result.addAll(playlist.getChannels());
        }
        // Solo Lista 1 y Lista 2 (0.6.16, pedido del usuario): los canales TvVoo/Highfly vienen
        // escritos en su lista; el catálogo web ya no agrega canales «virtuales» fuera de ellas,
        // solo completa el logo de los que trae una lista encendida.
        List<Channel> publishedProviderChannels = publishedPlaybackCatalog.activeProviderChannels();
        result = new ArrayList<>(TvVooChannelMerge.merge(
                result,
                publishedProviderChannels,
                false
        ));
        result = new ArrayList<>(HighflyChannelMerge.merge(
                result,
                publishedProviderChannels,
                false
        ));
        return publishedPlaybackCatalog.applyToPlayback(result);
    }

    boolean isWaitingForPublishedCatalog() {
        return publishedCatalogRefreshPending && !publishedCatalogWaitExpired && channels.isEmpty();
    }

    /** Muestra la lista guardada mientras se esperaba el catálogo, si aún no hay canales. */
    void applyPlaylistsAfterCatalogWait() {
        mainHandler.removeCallbacks(publishedCatalogWaitTimeout);
        if (isFinishing() || isDestroyed() || !channels.isEmpty() || playlistsBySource.isEmpty()) {
            return;
        }
        applyPlaylists(
                new LinkedHashMap<>(playlistsBySource),
                loadedPlaylistSignature,
                playlistGeneration,
                false
        );
        if (!channels.isEmpty()) hidePlaylistLoadingIfPlaybackPending();
    }

    /**
     * Desde la 0.6.16 los canales de proveedor viven dentro de las listas: sin ninguna lista
     * encendida no hay canales, así que ya no cuentan como fuente aparte.
     */
    boolean hasPublishedProviderChannels() {
        return false;
    }







    void refreshPublishedPlaybackCatalog() {
        if (publishedPlaybackCatalogRepository == null || catalogRefreshRunning) return;
        catalogRefreshRunning = true;
        boolean firstLoad = appliedCatalogDocument.isEmpty();
        if (publishedPlaybackCatalog.isEmpty()) publishedCatalogRefreshPending = true;
        PublishedPlaybackCatalogRepository repository = publishedPlaybackCatalogRepository;
        catalogExecutor.execute(() -> {
            PublishedPlaybackCatalog refreshed = null;
            String document = null;
            // Lo leído del disco (0.6.17): si la red trae el mismo documento no se vuelve a leer.
            String knownLayout = null;
            PublishedPlaybackCatalog knownCatalog = null;
            try {
                if (firstLoad) {
                    // Arranque instantáneo (0.5.85): lo último conocido mientras llega la red.
                    long now = System.currentTimeMillis();
                    String links = repository.cached(PublishedPlaybackCatalogRepository.HIGHFLY_LINKS_FILE);
                    if (links != null) PublishedHighflyLinks.update(links, now);
                    String variants = repository.cached(PublishedPlaybackCatalogRepository.TVVOO_VARIANTS_FILE);
                    if (variants != null) PublishedTvVooVariants.update(variants, now);
                    // Recién ahora: con las hermanas TvVoo y los enlaces Highfly cargados, la
                    // resolución temprana ve las mismas versiones (y la elegida) que la apertura.
                    mainHandler.post(this::warmLastChannel);
                    String cachedLayout = repository.cached(PublishedPlaybackCatalogRepository.LAYOUT_FILE);
                    if (cachedLayout != null) {
                        try {
                            long started = SystemClock.elapsedRealtime();
                            PublishedPlaybackCatalog cached = PublishedPlaybackCatalog.parse(cachedLayout);
                            Log.i(PlaybackStartupAnalytics.LOG_TAG, "catalogo en disco leido en "
                                    + (SystemClock.elapsedRealtime() - started) + " ms ("
                                    + cached.getRows().size() + " filas)");
                            knownLayout = cachedLayout;
                            knownCatalog = cached;
                            if (!cached.isEmpty()) {
                                mainHandler.post(() -> applyPublishedCatalog(cached, cachedLayout, false));
                            }
                        } catch (Exception error) {
                            Log.w(RESOURCE_HEALTH_TAG, "Layout publicado en caché dañado", error);
                            // Un archivo dañado se ignora: se espera la red como antes.
                        }
                    }
                }
                // Las tres descargas en paralelo: ninguna espera a la otra.
                executeUnlessClosing(catalogExecutor, () -> {
                    try {
                        // Enlaces directos Highfly: el canal Highfly abre sin consultar la API.
                        PublishedHighflyLinks.update(repository.fetchHighflyLinks(),
                                System.currentTimeMillis());
                    } catch (Exception error) {
                        Log.w(RESOURCE_HEALTH_TAG, "Enlaces Highfly publicados no disponibles", error);
                        // Sin enlaces publicados, Highfly se resuelve como siempre.
                    }
                });
                executeUnlessClosing(catalogExecutor, () -> {
                    try {
                        // Hermanas de cada canal TvVoo (HD, FHD, BACKUP): respaldo si la elegida cae.
                        PublishedTvVooVariants.update(repository.fetchTvVooVariants(),
                                System.currentTimeMillis());
                    } catch (Exception error) {
                        Log.w(RESOURCE_HEALTH_TAG, "Variantes TvVoo publicadas no disponibles", error);
                        // Sin variantes, cada canal TvVoo usa solo su versión elegida.
                    }
                });
                try {
                    document = repository.fetchLayoutDocument();
                    if (knownCatalog != null && document.equals(knownLayout)) {
                        refreshed = knownCatalog;
                    } else {
                        long started = SystemClock.elapsedRealtime();
                        refreshed = PublishedPlaybackCatalog.parse(document);
                        Log.i(PlaybackStartupAnalytics.LOG_TAG, "catalogo de red leido en "
                                + (SystemClock.elapsedRealtime() - started) + " ms");
                    }
                } catch (Exception error) {
                    Log.w(RESOURCE_HEALTH_TAG, "Layout publicado no se pudo descargar", error);
                    refreshed = null;
                }
            } finally {
                PublishedPlaybackCatalog result = refreshed;
                String resultDocument = document;
                mainHandler.post(() -> {
                    catalogRefreshRunning = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (result == null) {
                        // Sin red se conserva el último catálogo conocido (de disco o de antes).
                        if (!publishedPlaybackCatalog.isEmpty()) return;
                        publishedPlaybackCatalog = PublishedPlaybackCatalog.empty();
                        publishedCatalogRefreshPending = false;
                        if (getPlaylistSources().isEmpty() && !settingsOpen) {
                            openSettings();
                            return;
                        }
                        applyPlaylistsAfterCatalogWait();
                        return;
                    }
                    catalogUpdatedAtMillis = System.currentTimeMillis();
                    applyPublishedCatalog(result, resultDocument, true);
                });
            }
        });
    }

    /**
     * Aplica un catálogo publicado. Uno guardado en disco solo se usa mientras no haya llegado el
     * de la red; uno idéntico al ya aplicado no vuelve a armar la lista.
     */
    void applyPublishedCatalog(PublishedPlaybackCatalog result, String document,
            boolean fromNetwork) {
        if (isFinishing() || isDestroyed() || result == null) return;
        if (!fromNetwork && !appliedCatalogDocument.isEmpty()) return;
        String key = document == null ? "" : document;
        publishedCatalogRefreshPending = false;
        mainHandler.removeCallbacks(publishedCatalogWaitTimeout);
        if (!key.isEmpty() && key.equals(appliedCatalogDocument)) return;
        appliedCatalogDocument = key;
        publishedPlaybackCatalog = result;
        List<PlaylistSource> sources = getPlaylistSources();
        if (sources.isEmpty() && !hasPublishedProviderChannels()) {
            if (!settingsOpen) openSettings();
            return;
        }
        if (playlistsBySource.isEmpty() && !hasPublishedProviderChannels()) return;
        boolean firstList = channels.isEmpty();
        applyPlaylists(
                new LinkedHashMap<>(playlistsBySource),
                loadedPlaylistSignature,
                playlistGeneration,
                false
        );
        if (firstList && !channels.isEmpty()) hidePlaylistLoadingIfPlaybackPending();
    }


    /**
     * BACK que cerró la Guía o el selector al bajar (0.5.96). Con «atrás predictivo» el sistema
     * invoca el callback de atrás al soltar la tecla; sin esto, cerrar la Guía con BACK abría
     * además «¿Salir de la app?».
     */
    boolean backDownConsumedByPanel;
    long backDownConsumedAtElapsed;

    /** True (una sola vez) si el BACK que se suelta ya cerró un panel al bajar. */
    boolean takeBackConsumedByPanel() {
        boolean consumed = backDownConsumedByPanel
                && SystemClock.elapsedRealtime() - backDownConsumedAtElapsed < 2_000L;
        backDownConsumedByPanel = false;
        return consumed;
    }

    /** El BACK bajado cerró un panel: el callback de atrás (al soltar) no hace nada más. */
    void markBackConsumedByPanel() {
        backDownConsumedByPanel = true;
        backDownConsumedAtElapsed = SystemClock.elapsedRealtime();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean okKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !okKey) {
            rapidOkSequence.reset();
        }
        if (logoSizeAdjusting && handleLogoSizeKey(event)) return true;
        if (audioSyncAdjusting && handleAudioSyncKey(event)) return true;
        if (isGuideVisible() && handleGuideKey(event)) return true;
        if (isQualityUpgradeVisible() && handleQualityUpgradeKey(event)) return true;
        if (isPlaybackErrorVisible() && handlePlaybackErrorKey(event)) return true;
        if (isSourceSelectorVisible()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getRepeatCount() == 0) {
                    closePlaybackSourceSelector();
                    markBackConsumedByPanel();
                    return true;
                }
                if (minimalSelector() && (keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    moveMinimalVertical(keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    return true;
                }
                if (minimalSelector() && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    moveMinimalHorizontal(1);
                    return true;
                }
                if (minimalSelector() && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    // En la primera opción de una fila, ◀ cierra como antes.
                    if (!moveMinimalHorizontal(-1)) closePlaybackSourceSelector();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                    moveSourceSelectorFocus(-1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    moveSourceSelectorFocus(1);
                    return true;
                }
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER)
                        && event.getRepeatCount() == 0) {
                    selectFocusedSource();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    closePlaybackSourceSelector();
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                    || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                return true;
            }
        }
        if (isChannelNavigationKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // Android TV remotes emit repeated ACTION_DOWN events while
                // a channel key is held. Holding Channel +/− (and the D-pad
                // aliases) scrolls through channels continuously; only the
                // channel where the user stops is opened (0.5.85).
                zapBy(channelNavigationDelta(keyCode), event.getRepeatCount() > 0);
            }
            // Consume ACTION_UP as well so the focused player/view cannot
            // reinterpret the release as another navigation action.
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_GUIDE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                openGuide();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                openPlaybackSourceSelector();
            }
            return true;
        }

        if (okKey && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && !loadFailed && !settingsOpen && !channels.isEmpty()
                && rapidOkSequence.registerPress(SystemClock.uptimeMillis())) {
            mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
            hideProgrammeDetail.run();
            playChannel(0);
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
                handleBackAction();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                openSettings();
                return true;
            }
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.getRepeatCount() >= 1) {
                openSettings();
                return true;
            }
            if (event.getRepeatCount() == 0) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_INFO) {
                    if (loadFailed) {
                        refreshPlaylists(getPlaylistSources());
                    } else if (isProgrammeDetailVisible()) {
                        // Un nuevo OK cierra el detalle y deja el OSD con su tiempo normal.
                        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
                        hideProgrammeDetail.run();
                        showOverlay(false);
                    } else if (keyCode == KeyEvent.KEYCODE_INFO
                            || channelOverlay.getVisibility() == View.VISIBLE) {
                        // Segundo OK (o INFO): detalle del programa sobre el OSD.
                        startPlaybackFromInput();
                        showProgrammeDetail();
                    } else {
                        startPlaybackFromInput();
                        showOverlay(false);
                    }
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                startPlaybackFromInput();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }


    int channelNavigationDelta(int keyCode) {
        boolean inverted = isChannelNavigationInverted();
        boolean movesUp = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP;
        if (movesUp) return inverted ? 1 : -1;
        return inverted ? -1 : 1;
    }

    void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBackAction);
        }
    }

    // ---- Pantalla de error y avisos breves (0.5.99, «Flotante minimalista») -------------------



    /**
     * Encarga una tarea a un ejecutor que puede estar apagándose (la app se cierra): en ese
     * caso se descarta. Antes, RejectedExecutionException cerraba la app al salir (0.6.00).
     */
    static void executeUnlessClosing(java.util.concurrent.Executor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // La actividad se está destruyendo: la descarga ya no sirve.
        }
    }

    void handleBackAction() {
        // El sistema invoca esto al soltar BACK: si al bajar ya cerró la Guía o el selector,
        // no se sigue con el OSD ni con «¿Salir de la app?».
        if (takeBackConsumedByPanel()) return;
        if (audioSyncAdjusting) {
            // Atrás cancela el ajuste de sincronía (0.6.31) y queda el video. Se llega aquí al
            // bajar la tecla; el callback de atrás al soltarla no debe abrir «¿Salir de la app?».
            finishAudioSyncAdjust(false);
            markBackConsumedByPanel();
            return;
        }
        if (isPlaybackErrorVisible()) {
            // La pantalla de error se cierra y queda el video (o el negro) sin más.
            hidePlaybackErrorScreen();
            return;
        }
        if (isQualityUpgradeVisible()) {
            hideQualityUpgrade();
            return;
        }
        if (isSourceSelectorVisible()) {
            closePlaybackSourceSelector();
            return;
        }
        if (isProgrammeDetailVisible()) {
            mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
            hideProgrammeDetail.run();
            overlayAwaitingPlayback = false;
            mainHandler.removeCallbacks(hideOverlay);
            mainHandler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS);
            return;
        }
        if (channelOverlay.getVisibility() == View.VISIBLE
                || clock.getVisibility() == View.VISIBLE) {
            overlayAwaitingPlayback = false;
            mainHandler.removeCallbacks(hideOverlay);
            hideOverlay.run();
            return;
        }
        showExitDialog();
    }

    void showExitDialog() {
        if (exiting || (exitDialog != null && exitDialog.isShowing())) return;

        if (classicUi) {
            exitDialog = new Dialog(this);
            exitDialog.setContentView(R.layout.classic_dialog_exit);
            exitDialog.setCanceledOnTouchOutside(false);
        } else {
            // Moderno: diálogo «escena» sobre el video (2026-10-03).
            exitDialog = SceneDialog.create(this, R.layout.dialog_exit);
        }

        Button stayButton = exitDialog.findViewById(R.id.stay_button);
        Button exitButton = exitDialog.findViewById(R.id.exit_button);
        stayButton.setOnClickListener(view -> exitDialog.dismiss());
        exitButton.setOnClickListener(view -> exitApplication());
        // El foco parte en «Salir» en ambos estilos (pedido del usuario, 2026-10-03).
        exitDialog.setOnShowListener(dialog -> exitButton.requestFocus());
        exitDialog.setOnDismissListener(dialog -> {
            exitDialog = null;
            if (!exiting) enterImmersiveMode();
        });

        Window window = exitDialog.getWindow();
        if (classicUi && window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.68f;
            window.setAttributes(attributes);
        }
        exitDialog.show();
        if (!classicUi) SceneDialog.hideSystemBars(exitDialog);
    }

    void exitApplication() {
        if (exiting) return;
        exiting = true;
        releaseAppResources();
        closeApplicationTasksAndProcess();
    }

    void releaseAppResources() {
        if (resourcesReleased) return;
        resourcesReleased = true;
        closePlaybackSourceSelector();
        if (playbackBufferManager != null) {
            playbackBufferManager.close();
            playbackBufferManager = null;
        }
        startupMetrics.finish();
        playlistGeneration++;
        playbackGeneration++;
        cancelPlaybackWatchdog();
        cancelPlaybackResolution();
        resolverCoordinator.clear();
        if (streamResolverRegistry != null) streamResolverRegistry.clearSensitiveState();
        mainHandler.removeCallbacksAndMessages(null);
        if (contentTitle != null) contentTitle.release();

        if (exitDialog != null) {
            exitDialog.dismiss();
            exitDialog = null;
        }
        if (premiumSceneDialog != null) {
            premiumSceneDialog.dismiss();
            premiumSceneDialog = null;
        }
        cancelQualityUpgrade();
        stopWaitingForNetwork();
        HighflyPremiumCredentialStore premiumStore = HighflyPremiumCredentialStore.peek();
        if (premiumStore != null) premiumStore.clearSession();
        if (appUpdater != null) {
            appUpdater.destroy();
            appUpdater = null;
        }
        // Invalidate and cancel logo work before clearing the list/session
        // objects. The task may otherwise keep the previous channel and its
        // decoded response reachable until the executor thread exits.
        logoRequestGeneration++;
        if (logoRequestTask != null) {
            logoRequestTask.cancel(true);
            logoRequestTask = null;
        }
        networkExecutor.shutdownNow();
        playbackExecutor.shutdownNow();
        logoCacheExecutor.shutdownNow();
        resourceCacheExecutor.shutdownNow();
        catalogExecutor.shutdownNow();
        epgExecutor.shutdownNow();
        epgMergeExecutor.shutdownNow();
        prefetchExecutor.shutdownNow();

        if (playbackBitrateMeter != null) {
            playbackBitrateMeter.close();
            playbackBitrateMeter = null;
        }

        playbackChannel = null;
        discardCurrentPlaybackSource();
        channels.clear();
        playlistsBySource.clear();
        epgDataByUrl.clear();
        activeEpgUrls.clear();
        epgRequests.clear();
        resetEpgStaging();
        epgData = EpgData.empty();
        loadedPlaylistSignature = "";
        resolverChannelCounts = Collections.emptyMap();
        resolverSettingsSnapshotBeforeSettings = "";
        playlistSourcesSnapshotBeforeSettings = "";
        epgMergeInputSignature = "";
        startupPreferredChannelIdentity = "";
        startupSelectionPending = false;
        qualityPreferenceAppliedFor = null;
        subtitlePreferenceAppliedFor = null;
        subtitleTextObservedFor = null;

        if (channelLogo != null) {
            channelLogo.setImageDrawable(null);
        }
        displayedLogoIdentity = "";
        logoRevalidatedThisSession.clear();
        if (channelLogoCache != null) {
            channelLogoCache.clearSession();
        }

        if (player != null) {
            ExoPlayer releasedPlayer = player;
            player = null;
            if (playerView != null) {
                playerView.setPlayer(null);
            }
            try {
                releasedPlayer.stop();
                releasedPlayer.clearMediaItems();
            } finally {
                releasedPlayer.release();
            }
        }

        if (exiting) {
            SharedHttpClient.shutdownForProcessExit();
        }
    }

    void closeApplicationTasksAndProcess() {
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager != null) {
            for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
                task.finishAndRemoveTask();
            }
        }
        finishAndRemoveTask();

        // All app-owned resources have already been released above. Ending
        // our own process prevents Android TV from retaining this activity's
        // executor/Media3 heap as a cached process after explicit exit.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    Intent createSettingsIntent(int initialTab) {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(
                SettingsActivity.EXTRA_HAS_PUBLISHED_PROVIDER_CHANNELS,
                hasPublishedProviderChannels()
        );
        if (initialTab >= 0) {
            intent.putExtra(SettingsActivity.EXTRA_INITIAL_TAB, initialTab);
        }
        ArrayList<String> resolverIds = new ArrayList<>();
        ArrayList<Integer> resolverCounts = new ArrayList<>();
        for (ResolverDefinition definition : streamResolverRegistry.getDefinitions()) {
            resolverIds.add(definition.getId());
            Integer count = resolverChannelCounts.get(definition.getId());
            resolverCounts.add(count == null ? 0 : count);
        }
        intent.putStringArrayListExtra(SettingsActivity.EXTRA_RESOLVER_IDS, resolverIds)
                .putIntegerArrayListExtra(SettingsActivity.EXTRA_RESOLVER_COUNTS, resolverCounts)
                .putExtra(SettingsActivity.EXTRA_STATUS_CHANNELS, channels.size())
                .putExtra(SettingsActivity.EXTRA_STATUS_CATALOG_AT, catalogUpdatedAtMillis)
                .putExtra(SettingsActivity.EXTRA_STATUS_EPG_AT, epgUpdatedAtMillis)
                .putExtra(SettingsActivity.EXTRA_STATUS_HIGHFLY_COUNT, PublishedHighflyLinks.count())
                .putExtra(SettingsActivity.EXTRA_STATUS_HIGHFLY_AT,
                        PublishedHighflyLinks.generatedAtMillis());
        if (playbackDiagnosticsActive && videoInfo != null && codecInfo != null) {
            intent.putExtra(SettingsActivity.EXTRA_SIGNAL_VIDEO, videoInfo.getText().toString())
                    .putExtra(SettingsActivity.EXTRA_SIGNAL_CODECS, codecInfo.getText().toString());
        }
        if (channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return intent;

        Channel channel = channels.get(channelIndex);
        intent.putExtra(SettingsActivity.EXTRA_SIGNAL_FORMAT, signalFormatLabel())
                .putExtra(SettingsActivity.EXTRA_SIGNAL_RESOLVER, signalResolverLabel(channel))
                .putExtra(SettingsActivity.EXTRA_SIGNAL_BACKUP, signalBackupLabel(channel));
        intent.putExtra(SettingsActivity.EXTRA_CHANNEL_INDEX, channelIndex)
                .putExtra(SettingsActivity.EXTRA_CHANNEL_TVG_ID, channel.getTvgId())
                .putExtra(SettingsActivity.EXTRA_CHANNEL_NAME, channel.getName());
        if (!classicUi) {
            intent.putExtra(SettingsActivity.EXTRA_CHANNEL_NUMBER,
                    publishedPlaybackCatalog.numberFor(channel, channelIndex + 1));
            EpgProgramme programme = epgData.findCurrent(channel.getTvgId(), System.currentTimeMillis());
            if (programme != null) intent.putExtra(SettingsActivity.EXTRA_PROGRAMME_TITLE, programme.getTitle());
        }
        if (!classicUi && displayedLogoBitmap != null && displayedLogoKey != null) {
            intent.putExtra(SettingsActivity.EXTRA_CHANNEL_LOGO_KEY, displayedLogoKey);
        }

        List<VideoTrackOption> options = player == null
                ? Collections.emptyList()
                : collectVideoTrackOptions(player.getCurrentTracks());
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> bitrates = new ArrayList<>();
        ArrayList<Integer> widths = new ArrayList<>();
        ArrayList<Integer> heights = new ArrayList<>();
        for (VideoTrackOption option : options) {
            labels.add(option.label());
            bitrates.add(option.bitrate);
            widths.add(option.width);
            heights.add(option.height);
        }
        PlaybackPreferences.QualityPreference preference =
                playbackPreferences.getQuality(channel);
        boolean automaticQuality = playbackPreferences.isAutomaticQuality(channel);
        VideoTrackOption selectedOption = preference != null
                ? findClosestQuality(options, preference)
                : (automaticQuality || options.isEmpty() ? null : options.get(0));
        int selectedIndex = selectedOption == null ? -1 : options.indexOf(selectedOption);
        if (selectedIndex < 0 && options.size() == 1) selectedIndex = 0;

        intent.putStringArrayListExtra(SettingsActivity.EXTRA_QUALITY_LABELS, labels)
                .putIntegerArrayListExtra(SettingsActivity.EXTRA_QUALITY_BITRATES, bitrates)
                .putIntegerArrayListExtra(SettingsActivity.EXTRA_QUALITY_WIDTHS, widths)
                .putIntegerArrayListExtra(SettingsActivity.EXTRA_QUALITY_HEIGHTS, heights)
                .putExtra(SettingsActivity.EXTRA_QUALITY_SELECTED_INDEX, selectedIndex)
                .putExtra(SettingsActivity.EXTRA_QUALITY_AUTOMATIC, automaticQuality)
                .putExtra(
                        SettingsActivity.EXTRA_SUBTITLES_AVAILABLE,
                        hasObservedSubtitleText(channel)
                )
                .putExtra(
                        SettingsActivity.EXTRA_SUBTITLES_ENABLED,
                        playbackPreferences.getSubtitles(channel)
                );
        // Pistas de audio (0.6.11): la fila solo aparece si hay más de una.
        List<AudioTrackOptions.Option> audio = player == null
                ? Collections.emptyList()
                : AudioTrackOptions.collect(player.getCurrentTracks());
        Format playingAudio = player == null ? null : player.getAudioFormat();
        ArrayList<String> audioLabels = new ArrayList<>();
        ArrayList<String> audioKeys = new ArrayList<>();
        int audioSelected = -1;
        if (audio.size() > 1) {
            for (int index = 0; index < audio.size(); index++) {
                audioLabels.add(audio.get(index).label);
                audioKeys.add(audio.get(index).key);
                if (audio.get(index).selected && audioSelected < 0) audioSelected = index;
            }
        } else if (currentTsAudioSession != null && currentTsAudioSession.languages().size() > 1) {
            // TS con varios audios (0.6.12): Media3 ve uno solo; los idiomas vienen de la PMT y
            // elegir otro reabre el canal con el filtro aplicado.
            String playing = AudioTrackOptions.normalizeLanguage(
                    playingAudio == null ? null : playingAudio.language);
            // Reconectando todavía no hay formato: se marca el idioma que el filtro aplicó.
            if (playing.isEmpty()) playing = currentTsAudioSession.getAppliedLanguage();
            List<String> languages = currentTsAudioSession.languages();
            for (int index = 0; index < languages.size(); index++) {
                String language = languages.get(index);
                audioLabels.add(AudioTrackOptions.labelFor(language, "", 0, index + 1));
                audioKeys.add(TS_AUDIO_KEY_PREFIX + language);
                if (language.equals(playing)) audioSelected = index;
            }
        }
        if (audioLabels.size() > 1) {
            intent.putStringArrayListExtra(SettingsActivity.EXTRA_AUDIO_LABELS, audioLabels)
                    .putStringArrayListExtra(SettingsActivity.EXTRA_AUDIO_KEYS, audioKeys)
                    .putExtra(SettingsActivity.EXTRA_AUDIO_SELECTED_INDEX, audioSelected);
        }
        intent.putExtra(SettingsActivity.EXTRA_AUDIO_SYNC_MS, playbackPreferences.getAudioSyncMs(channel));
        String audioLanguage = playingAudio == null ? ""
                : AudioTrackOptions.languageName(AudioTrackOptions.normalizeLanguage(playingAudio.language));
        if (!audioLanguage.isEmpty()) {
            intent.putExtra(SettingsActivity.EXTRA_SIGNAL_AUDIO_LANGUAGE, audioLanguage);
        }
        return intent;
    }

    void openSettings() {
        openSettings(-1);
    }

    void openSettings(int initialTab) {
        if (settingsOpen) return;
        mainHandler.removeCallbacks(hideOverlay);
        hideOverlay.run();
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        hideProgrammeDetail.run();
        closeGuide();
        resolverSettingsSnapshotBeforeSettings = resolverSettingsSnapshot();
        playlistSourcesSnapshotBeforeSettings = playlistSourceSignature(getPlaylistSources());
        settingsOpen = true;
        if (!classicUi) {
            // Opciones flota sobre el video: sin OSD, error ni «CONECTANDO» debajo (0.6.02).
            mainHandler.removeCallbacks(showDeferredPlaybackError);
            if (isPlaybackErrorVisible()) {
                playbackErrorOverlay.setVisibility(View.GONE);
                playbackErrorDeferred = true;
            }
            if (loadingPanel != null) loadingPanel.setAlpha(0f);
        }
        startActivityForResult(createSettingsIntent(initialTab), SETTINGS_REQUEST);
    }

    void applyPlaybackSettingsResult(Intent data) {
        if (data == null
                || channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return;

        int expectedIndex = data.getIntExtra(
                SettingsActivity.EXTRA_CHANNEL_INDEX,
                -1
        );
        if (expectedIndex != channelIndex) return;
        Channel channel = channels.get(channelIndex);
        String expectedTvgId = data.getStringExtra(
                SettingsActivity.EXTRA_CHANNEL_TVG_ID
        );
        String expectedName = data.getStringExtra(SettingsActivity.EXTRA_CHANNEL_NAME);
        if (expectedTvgId != null && !AppStrings.isBlank(expectedTvgId)
                && !expectedTvgId.equals(channel.getTvgId())) return;
        if (expectedName != null && !expectedName.equals(channel.getName())) return;

        if (data.hasExtra(SettingsActivity.EXTRA_QUALITY_AUTOMATIC)) {
            boolean automatic = data.getBooleanExtra(
                    SettingsActivity.EXTRA_QUALITY_AUTOMATIC,
                    false
            );
            if (automatic) {
                playbackPreferences.useAutomaticQuality(channel);
            } else if (data.hasExtra(SettingsActivity.EXTRA_QUALITY_BITRATE)
                    && data.hasExtra(SettingsActivity.EXTRA_QUALITY_WIDTH)
                    && data.hasExtra(SettingsActivity.EXTRA_QUALITY_HEIGHT)) {
                playbackPreferences.rememberQuality(
                        channel,
                        data.getIntExtra(SettingsActivity.EXTRA_QUALITY_BITRATE, 0),
                        data.getIntExtra(SettingsActivity.EXTRA_QUALITY_WIDTH, 0),
                        data.getIntExtra(SettingsActivity.EXTRA_QUALITY_HEIGHT, 0)
                );
            }
            qualityPreferenceAppliedFor = null;
            if (player != null) applySavedQualityPreference(player.getCurrentTracks());
        }

        if (data.hasExtra(SettingsActivity.EXTRA_SUBTITLES_ENABLED)) {
            playbackPreferences.rememberSubtitles(
                    channel,
                    data.getBooleanExtra(SettingsActivity.EXTRA_SUBTITLES_ENABLED, true)
            );
            subtitlePreferenceAppliedFor = null;
            if (player != null) applySavedSubtitlePreference(player.getCurrentTracks());
        }

        if (data.hasExtra(SettingsActivity.EXTRA_AUDIO_KEY)) {
            String key = data.getStringExtra(SettingsActivity.EXTRA_AUDIO_KEY);
            if (key != null && key.startsWith(TS_AUDIO_KEY_PREFIX)) {
                // Idioma de un TS con varios audios: se guarda y el canal se reabre, porque
                // Media3 lee la tabla de pistas una sola vez por apertura.
                String language = key.substring(TS_AUDIO_KEY_PREFIX.length());
                playbackPreferences.rememberAudio(
                        channel, AudioTrackOptions.keyFor(language, 0, ""));
                playChannel(channelIndex);
            } else {
                playbackPreferences.rememberAudio(channel, key);
                if (player != null) applySavedAudioPreference(player.getCurrentTracks());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (appUpdater != null && appUpdater.onActivityResult(requestCode)) return;
        if (requestCode == SETTINGS_REQUEST) {
            settingsOpen = false;
            if (!classicUi && loadingPanel != null) {
                loadingPanel.setAlpha(blackScreenDimmed ? BLACK_SCREEN_ALPHA : 1f);
            }
            if (playbackErrorDeferred) mainHandler.post(showDeferredPlaybackError);
            // Opciones movió la sincronía en vivo: vale lo guardado (o lo de antes, si no se guardó).
            restoreAudioSyncForCurrentChannel();
            // Opciones › Canales puede volver a mostrar canales ocultos (0.5.90).
            String previousVisibility = channelVisibility.signature();
            channelVisibility = new ChannelVisibility(this);
            if (!previousVisibility.equals(channelVisibility.signature())) {
                mainHandler.post(this::reapplyChannelList);
            }
            // Con la configuración abierta la precarga de vecinos se detiene: se retoma al volver.
            if (playbackHasStarted) scheduleNeighborPrefetch();
            String resolverSnapshotBefore = resolverSettingsSnapshotBeforeSettings;
            String playlistSnapshotBefore = playlistSourcesSnapshotBeforeSettings;
            resolverSettingsSnapshotBeforeSettings = "";
            playlistSourcesSnapshotBeforeSettings = "";
            List<PlaylistSource> sources = getPlaylistSources();
            if (resultCode == RESULT_OK && (!sources.isEmpty() || hasPublishedProviderChannels())) {
                applyPlaybackSettingsResult(data);
                reloadResolverRegistry();
                boolean resolverConfigurationChanged = AppStrings.isBlank(resolverSnapshotBefore)
                        || !resolverSnapshotBefore.equals(resolverSettingsSnapshot());
                boolean playlistConfigurationChanged = AppStrings.isBlank(playlistSnapshotBefore)
                        || !playlistSnapshotBefore.equals(playlistSourceSignature(sources));
                if (resolverConfigurationChanged || playlistConfigurationChanged) {
                    resolverCoordinator.clear();
                }
                boolean normalizationChanged =
                        playerUsesVolumeNormalization != isVolumeNormalizationEnabled()
                                || playerUsesSoftwareMp2 != isSoftwareMp2Enabled();
                if (normalizationChanged) {
                    if (playbackBitrateMeter != null) {
                        playbackBitrateMeter.close();
                        playbackBitrateMeter = null;
                    }
                    if (player != null) {
                        player.release();
                        player = null;
                    }
                    createPlayer();
                    channels.clear();
                    playlistsBySource.clear();
                    loadedPlaylistSignature = "";
                    activeEpgUrls.clear();
                    epgDataByUrl.clear();
                    epgRequests.clear();
        resetEpgStaging();
                    epgData = EpgData.empty();
                }
                // Salir de Opciones sin cambiar listas ni proveedores no recarga nada.
                refreshAfterSettings = resolverConfigurationChanged || playlistConfigurationChanged
                        || normalizationChanged || channels.isEmpty();
                openSourceSelectorAfterSettings = data != null && data.getBooleanExtra(
                        SettingsActivity.EXTRA_OPEN_SOURCE_SELECTOR, false);
                adjustLogoSizeAfterSettings = data != null && data.getBooleanExtra(
                        SettingsActivity.EXTRA_ADJUST_LOGO_SIZE, false);
                adjustAudioSyncAfterSettings = data != null && data.getBooleanExtra(
                        SettingsActivity.EXTRA_ADJUST_AUDIO_SYNC, false);
            } else if (sources.isEmpty() && !hasPublishedProviderChannels()) {
                openSettings();
            }
        }
    }

    List<PlaylistSource> getPlaylistSources() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        String url1 = prefs.getString(SettingsActivity.KEY_PLAYLIST_URL, "");
        String url2 = prefs.getString(SettingsActivity.KEY_PLAYLIST_URL_2, "");
        boolean enabled1 = prefs.contains(SettingsActivity.KEY_PLAYLIST_ENABLED)
                ? prefs.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, true)
                : url1 != null && !AppStrings.isBlank(url1);
        boolean enabled2 = prefs.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false);
        List<PlaylistSource> sources = new ArrayList<>();
        if (enabled1 && url1 != null && !url1.trim().isEmpty()) {
            sources.add(new PlaylistSource(1, url1));
        }
        if (enabled2 && url2 != null && !url2.trim().isEmpty()) {
            sources.add(new PlaylistSource(2, url2));
        }
        return sources;
    }

    String playlistSourceSignature(List<PlaylistSource> sources) {
        String signature = PlaylistSource.signature(sources);
        return AppStrings.isBlank(signature) ? "sources=none" : signature;
    }

    String resolverSettingsSnapshot() {
        if (streamResolverRegistry == null) return "resolver=unavailable";
        StringBuilder snapshot = new StringBuilder(
                streamResolverRegistry.getCatalogVersion()
        );
        for (ResolverDefinition definition : streamResolverRegistry.getDefinitions()) {
            snapshot.append('|')
                    .append(definition.getId())
                    .append(':')
                    .append(definition.getEngine())
                    .append('=')
                    .append(resolverPreferences == null
                            || resolverPreferences.isEnabled(definition));
        }
        if (resolverPreferences != null && resolverPreferences.mediaFlowPreferences() != null) {
            snapshot.append("|mediaflow=")
                    .append(resolverPreferences.mediaFlowPreferences().signature());
        }
        if (resolverPreferences != null) {
            snapshot.append("|highfly-manifest=")
                    .append(resolverPreferences.highflyManifestUrl());
        }
        return snapshot.toString();
    }

    boolean isChannelNavigationInverted() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_INVERT_CHANNEL_KEYS, false);
    }

    boolean isVolumeNormalizationEnabled() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_NORMALIZE_VOLUME, false);
    }

    /**
     * Opciones › Información de la señal (0.6.34): formato del stream. Primero lo que el
     * reproductor cargó (manifiesto HLS/DASH); si no, el tipo o la extensión de la fuente.
     */
    String signalFormatLabel() {
        Object manifest = player == null ? null : player.getCurrentManifest();
        if (manifest instanceof androidx.media3.exoplayer.hls.HlsManifest) return "HLS";
        if (manifest instanceof androidx.media3.exoplayer.dash.manifest.DashManifest) return "DASH";
        ResolvedPlaybackSource source = currentPlaybackSource;
        if (source == null) return "";
        String mime = source.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.contains("mpegurl")) return "HLS";
        if (mime.contains("dash")) return "DASH";
        if (mime.contains("mp2t")) return "MPEG-TS";
        if (mime.contains("mp4")) return "MP4";
        URI uri = source.getPlaybackUri();
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return "HLS";
        if (path.endsWith(".mpd")) return "DASH";
        if (path.endsWith(".ts")) return "MPEG-TS";
        if (path.endsWith(".mp4")) return "MP4";
        return "";
    }

    /** Resolutor que abrió el canal; «Directo» sin resolutor; marca si suena el respaldo. */
    String signalResolverLabel(Channel channel) {
        ResolvedPlaybackSource source = currentPlaybackSource;
        String id = source != null && source.hasResolver() ? source.getResolverId() : "";
        if (AppStrings.isBlank(id) && channel != null) {
            String attribute = channel.getAttributes().get("x-resolver");
            id = attribute == null ? "" : attribute;
        }
        String name = resolverDisplayName(id);
        if (tvvooBackupActive && channel != null) {
            String backupName = TvVooBackup.hasTvVoo(channel)
                    ? "TvVoo" : getString(R.string.signal_resolver_direct);
            return getString(R.string.signal_resolver_on_backup, backupName);
        }
        return name;
    }

    String resolverDisplayName(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "": return getString(R.string.signal_resolver_direct);
            case "highfly": return "Highfly";
            case "tvvoo": return "TvVoo";
            case "nauta": return "Nauta";
            case "m6foot": return "M6Foot";
            case "tvn": return "TVN";
            case "meganoticias": return "Meganoticias";
            case "mediaflow": return "MediaFlow";
            default: return Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
    }

    /** Respaldo del canal; vacío si no tiene. */
    String signalBackupLabel(Channel channel) {
        if (channel == null) return "";
        if (TvVooBackup.hasTvVoo(channel)) return "TvVoo";
        int direct = TvVooBackup.directBackupsOf(channel).size();
        if (direct == 1) return getString(R.string.signal_resolver_direct);
        return direct > 1 ? getString(R.string.signal_backup_direct_count, direct) : "";
    }

    boolean isSoftwareMp2Enabled() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SOFTWARE_MP2, true);
    }

    void restoreAudioSyncForCurrentChannel() {
        if (playbackPreferences == null || channelIndex < 0 || channelIndex >= channels.size()) {
            AudioSyncOffset.setMs(0);
            return;
        }
        AudioSyncOffset.setMs(playbackPreferences.getAudioSyncMs(channels.get(channelIndex)));
    }

    boolean isAutoReconnectEnabled() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_AUTO_RECONNECT, true);
    }

    boolean isNetworkAvailable() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return true;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }


    /** Estilo clásico (0.5.40): reloj en pastilla, carga en texto y selector en panel. */
    /** Difuminado que sube desde abajo (moderno, escenario 0.6.22): sin bordes ni panel. */
    void applyMinimalSelectorShade() {
        View shade = findViewById(R.id.source_selector_shade);
        if (shade == null) return;
        android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable();
        gradient.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP);
        gradient.setColors(
                new int[]{0xF2000000, 0xE0000000, 0x8C000000, 0x00000000},
                new float[]{0f, 0.3f, 0.48f, 0.7f});
        shade.setBackground(gradient);
    }

    void applyClassicChrome() {
        clock.setBackgroundResource(R.drawable.classic_panel_background);
        clock.setPadding(dpToPx(16), dpToPx(9), dpToPx(16), dpToPx(9));
        clock.setTextColor(getColor(R.color.white));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        clock.setShadowLayer(4f, 1f, 2f, 0xA0000000);
        FrameLayout.LayoutParams clockParams = (FrameLayout.LayoutParams) clock.getLayoutParams();
        clockParams.topMargin = dpToPx(24);
        clock.setLayoutParams(clockParams);
        View loadingProgress = findViewById(R.id.loading_progress);
        if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
        loadingText.setTextColor(getColor(R.color.white));
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        loadingText.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        loadingText.setAllCaps(false);
        loadingText.setLetterSpacing(0f);
        View selectorPanel = findViewById(R.id.source_selector_panel);
        if (selectorPanel != null) {
            selectorPanel.setBackgroundResource(R.drawable.classic_source_selector_background);
            selectorPanel.setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(14));
            FrameLayout.LayoutParams panelParams =
                    (FrameLayout.LayoutParams) selectorPanel.getLayoutParams();
            panelParams.width = dpToPx(500);
            panelParams.gravity = Gravity.CENTER;
            panelParams.setMargins(0, 0, 0, 0);
            selectorPanel.setLayoutParams(panelParams);
        }
        for (int id : new int[]{R.id.source_selector_dim, R.id.source_selector_scrim_start,
                R.id.source_selector_scrim, R.id.source_selector_shade}) {
            View scrim = findViewById(id);
            if (scrim != null) scrim.setVisibility(View.GONE);
        }
        LinearLayout hero = findViewById(R.id.source_selector_hero);
        if (hero != null) hero.setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout.LayoutParams) sourceSelectorStatus.getLayoutParams()).setMarginStart(0);
        sourceSelectorStatus.setTypeface(android.graphics.Typeface.DEFAULT);
        sourceSelectorChannel.setTypeface(android.graphics.Typeface.DEFAULT);
        sourceSelectorChannel.setAllCaps(false);
        sourceSelectorChannel.setLetterSpacing(0f);
        sourceSelectorChannel.setTextColor(getColor(R.color.white));
        // El layout lleva la escala del moderno (0.6.18); el clásico conserva la suya.
        sourceSelectorChannel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        sourceSelectorStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sourceSelectorStatus.setTextColor(0xA6FFFFFF);
        sourceSelectorStatus.setLineSpacing(0f, 1.3f);
        ((LinearLayout.LayoutParams) sourceSelectorStatus.getLayoutParams()).topMargin = dpToPx(9);
        ((LinearLayout.LayoutParams) sourceSelectorTitle.getLayoutParams()).topMargin = dpToPx(9);
        View classicScrollFrame = (View) findViewById(R.id.source_selector_scroll).getParent();
        ((LinearLayout.LayoutParams) classicScrollFrame.getLayoutParams()).topMargin = dpToPx(14);
        sourceSelectorTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.settings_heading_text_size));
        sourceSelectorTitle.setTextColor(getColor(R.color.cyan));
        sourceSelectorCloseButton.setVisibility(View.VISIBLE);
        sourceSelectorCloseButton.setBackgroundResource(R.drawable.classic_focus_button);
        sourceSelectorCloseButton.setTextColor(getColorStateList(R.color.classic_focus_button_text));
        LinearLayout.LayoutParams closeParams =
                (LinearLayout.LayoutParams) sourceSelectorCloseButton.getLayoutParams();
        closeParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
        closeParams.height = dpToPx(40);
        sourceSelectorCloseButton.setLayoutParams(closeParams);
    }






    void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Al volver a la app (TV encendida de nuevo), catálogo y enlaces se renuevan si son viejos.
        if (!settingsOpen && catalogUpdatedAtMillis > 0L
                && System.currentTimeMillis() - catalogUpdatedAtMillis > CATALOG_REFRESH_ON_START_MS) {
            refreshPublishedPlaybackCatalog();
        }
        if (restartPlaybackAfterFocusLoss) {
            restartPlaybackAfterFocusLoss = false;
            if (channels.isEmpty()) {
                refreshPlaylists(getPlaylistSources());
            } else {
                restartCurrentPlaybackAfterFocusLoss();
            }
            return;
        }
        if (!settingsOpen && !refreshAfterSettings) {
            List<PlaylistSource> sources = getPlaylistSources();
            refreshPlaylists(sources);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (UiStyle.isClassic(this) != classicUi) {
            // Se cambió el estilo en Opciones: se recarga la pantalla con el nuevo.
            recreate();
            return;
        }
        enterImmersiveMode();
        if (appUpdater != null) appUpdater.onHostResume();
        if (refreshAfterSettings) {
            refreshAfterSettings = false;
            refreshPlaylists(getPlaylistSources());
        }
        if (restartPlaybackAfterFocusLoss && !settingsOpen) {
            // Pausa sin pasar por onStop (0.6.07): un intent con la app abierta, como «Ver» de
            // un recordatorio del canal actual o de uno que ya no está. onStart no corre, así
            // que el canal se reabre aquí; si el intent ya abrió otro canal, no se toca.
            restartPlaybackAfterFocusLoss = false;
            if (playbackChannel == null) restartCurrentPlaybackAfterFocusLoss();
        }
        startPlaybackFromInput();
        updateDiagnosticsVisibility();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && openSourceSelectorAfterSettings && !settingsOpen) {
            openSourceSelectorAfterSettings = false;
            mainHandler.post(this::openPlaybackSourceSelector);
        }
        if (hasFocus && adjustLogoSizeAfterSettings && !settingsOpen) {
            adjustLogoSizeAfterSettings = false;
            mainHandler.post(this::startLogoSizeAdjust);
        }
        if (hasFocus && adjustAudioSyncAfterSettings && !settingsOpen) {
            adjustAudioSyncAfterSettings = false;
            mainHandler.post(this::startAudioSyncAdjust);
        }
        updateDiagnosticsVisibility();
        if (hasFocus && !resourcesReleased) {
            maybeActivatePlaybackDiagnostics();
            updateDiagnostics();
        }
    }

    @Override
    protected void onPause() {
        if (appUpdater != null) appUpdater.onHostPause();
        if (playbackBitrateMeter != null) playbackBitrateMeter.setNotificationsEnabled(false);
        closePlaybackSourceSelector();
        resetResourceWarningState();
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        hideProgrammeDetail.run();
        closeGuide();
        if (!settingsOpen) {
            stopPlaybackForFocusLoss();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (!settingsOpen && !isFinishing() && !resourcesReleased) {
            stopPlaybackForFocusLoss();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releaseAppResources();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        handleMemoryPressure(level);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        handleMemoryPressure(PlaybackResourceWarningPolicy.LOW_MEMORY_CALLBACK_LEVEL);
    }


}
