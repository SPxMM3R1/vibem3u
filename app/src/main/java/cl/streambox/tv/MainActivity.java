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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class MainActivity extends Activity {
    private static final int SETTINGS_REQUEST = 1001;
    private static final long OVERLAY_TIMEOUT_MS = 4_500;
    private static final long LIGHT_EPG_TIMEOUT_MS = 6_500;
    private static final long PLAYER_RETRY_DELAY_MS = 2_500;
    private static final long UPDATE_CHECK_DELAY_MS = 4_000;
    private static final long NO_RESOLUTION_REQUEST = -1L;
    private static final int PREMIUM_STABLE_SOURCE_POSITION = 3;
    private static final int PREMIUM_EVENT_SOURCE_POSITION = 4;
    private static final String PLAYER_USER_AGENT = "VibeM3U/0.4.42 (Android TV)";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(2);
    // Resolver calls can spend several seconds in provider HTTP/TLS
    // handshakes. Keep them out of the executor that refreshes the playlist,
    // EPG and logos so maintenance work cannot delay channel startup.
    private final ExecutorService playbackExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService logoCacheExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService resourceCacheExecutor = Executors.newSingleThreadExecutor();
    private PlaylistRepository repository;
    private EpgRepository epgRepository;
    private final PlaybackRecoveryPolicy playbackRecoveryPolicy = new PlaybackRecoveryPolicy();
    private final PlaybackRecoveryEpisode playbackRecoveryEpisode = new PlaybackRecoveryEpisode();
    private final PlaybackStartupMetrics startupMetrics = new PlaybackStartupMetrics();
    private PlaybackBufferManager playbackBufferManager;
    private final HighflyPremiumEventRecoveryPolicy temporaryEventRecoveryPolicy =
            new HighflyPremiumEventRecoveryPolicy();
    private final ResolverCoordinator resolverCoordinator = new ResolverCoordinator();
    private ResolverCatalogRepository resolverCatalogRepository;
    private ResolverPreferences resolverPreferences;
    private StreamResolverRegistry streamResolverRegistry;
    private HighflyPremiumCredentialStore highflyPremiumCredentialStore;
    private HighflyPremiumCatalogRepository highflyPremiumCatalogRepository;
    private Map<String, Integer> resolverChannelCounts = Collections.emptyMap();
    private final List<Channel> channels = new ArrayList<>();
    private final Map<Integer, Playlist> playlistsBySource = new LinkedHashMap<>();
    private final Map<String, EpgData> epgDataByUrl = new LinkedHashMap<>();
    private final Set<String> activeEpgUrls = new LinkedHashSet<>();
    private final Set<String> epgRequests = new HashSet<>();

    private PlayerView playerView;
    private PlaybackDiagnosticsWorker playbackBitrateMeter;
    private View channelOverlay;
    private View loadingPanel;
    private TextView loadingText;
    private TextView clock;
    private View lightEpgOverlay;
    private TextView lightEpgChannelNumber;
    private TextView lightEpgChannelName;
    private TextView lightEpgGroup;
    private TextView lightEpgClock;
    private ProgressBar lightEpgProgress;
    private TextView lightEpgCurrentTitle;
    private TextView lightEpgCurrentTime;
    private TextView lightEpgNextTitle;
    private TextView lightEpgNextTime;
    private ImageView channelLogo;
    private TextView channelLogoFallback;
    private TextView channelNumber;
    private TextView channelName;
    private ContinuousMarqueeTextView contentTitle;
    private TextView programmeTime;
    private ProgressBar liveProgress;
    private TextView videoInfo;
    private TextView codecInfo;
    private TextView statusDot;
    private TextView streamStatus;

    private ExoPlayer player;
    private AppUpdater appUpdater;
    private PlaybackPreferences playbackPreferences;
    private ChannelLogoCache channelLogoCache;
    private EpgData epgData = EpgData.empty();
    private String loadedPlaylistSignature = "";
    private int channelIndex;
    private boolean loadFailed;
    private boolean settingsOpen;
    private String resolverSettingsSnapshotBeforeSettings = "";
    private String playlistSourcesSnapshotBeforeSettings = "";
    private boolean refreshAfterSettings;
    private boolean overlayAwaitingPlayback;
    private boolean exiting;
    private boolean resourcesReleased;
    private Dialog exitDialog;
    private String qualityPreferenceAppliedFor;
    private String subtitlePreferenceAppliedFor;
    private String subtitleTextObservedFor;
    private int playlistGeneration;
    private long logoRequestGeneration;
    private Future<?> logoRequestTask;
    private String displayedLogoIdentity = "";
    private final Set<String> logoRevalidatedThisSession = new HashSet<>();
    private boolean playerUsesVolumeNormalization;
    private long playbackGeneration;
    private Runnable scheduledPlaybackRetry;
    private Future<?> playbackResolutionTask;
    private ResolutionContext playbackResolutionContext;
    private ManifestHandoffCache playbackManifestCache;
    private long playbackResolutionRequestId;
    private long activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    private Channel playbackChannel;
    private ResolvedPlaybackSource currentPlaybackSource;
    private String loadingMessageBase = "";
    private boolean loadingMessageAnimating;
    private boolean loadingAnimationScheduled;
    private int loadingDotCount;
    private boolean startupSelectionPending;
    private String startupPreferredChannelIdentity = "";
    private String epgMergeInputSignature = "";
    private long epgMergeGeneration;
    private boolean playbackDiagnosticsActive;
    private final AtomicBoolean diagnosticsUpdateQueued = new AtomicBoolean();
    private final Runnable applyMeasuredDiagnostics = () -> {
        diagnosticsUpdateQueued.set(false);
        if (exiting || resourcesReleased || player == null) return;
        maybeActivatePlaybackDiagnostics();
        updateDiagnostics();
    };

    private final Runnable hideOverlay = () -> {
        channelOverlay.setVisibility(View.GONE);
        clock.setVisibility(View.GONE);
        updateDiagnosticsVisibility();
    };
    private final Runnable hideLightEpg = () -> {
        if (lightEpgOverlay != null) {
            lightEpgOverlay.setVisibility(View.GONE);
        }
    };
    private final Runnable updateClock = new Runnable() {
        @Override public void run() {
            String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date());
            clock.setText(currentTime);
            mainHandler.postDelayed(this, 30_000);
        }
    };
    private final Runnable updateProgramme = new Runnable() {
        @Override public void run() {
            updateProgrammeInfo();
            if (!exiting && !isFinishing()) {
                mainHandler.postDelayed(this, 30_000);
            }
        }
    };
    private final Runnable animateLoadingText = new Runnable() {
        @Override public void run() {
            if (!loadingMessageAnimating
                    || loadingPanel == null
                    || loadingPanel.getVisibility() != View.VISIBLE) {
                loadingAnimationScheduled = false;
                return;
            }
            loadingAnimationScheduled = false;
            loadingDotCount = (loadingDotCount + 1) % 4;
            renderAnimatedLoadingText();
            if (loadingMessageAnimating
                    && loadingPanel.getVisibility() == View.VISIBLE) {
                loadingAnimationScheduled = true;
                mainHandler.postDelayed(this, 420L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SettingsActivity.ensureDefaultPlaylistConfigured(this);
        repository = new PlaylistRepository(this);
        epgRepository = new EpgRepository(this);
        channelLogoCache = new ChannelLogoCache(this);
        playbackPreferences = new PlaybackPreferences(this);
        resolverCatalogRepository = new ResolverCatalogRepository(this);
        resolverPreferences = new ResolverPreferences(this);
        highflyPremiumCredentialStore = HighflyPremiumCredentialStore.getInstance(this);
        highflyPremiumCatalogRepository = new HighflyPremiumCatalogRepository(this);
        reloadResolverRegistry();
        bindViews();
        registerBackCallback();
        enterImmersiveMode();
        createPlayer();
        if (BuildConfig.ENABLE_APP_UPDATES) {
            appUpdater = new AppUpdater(this, networkExecutor, mainHandler);
            mainHandler.postDelayed(appUpdater::checkForUpdates, UPDATE_CHECK_DELAY_MS);
        }
        updateClock.run();
    }

    private void reloadResolverRegistry() {
        if (streamResolverRegistry != null) streamResolverRegistry.clearSensitiveState();
        try {
            streamResolverRegistry = new StreamResolverRegistry(
                    resolverCatalogRepository.load(),
                    resolverPreferences,
                    highflyPremiumCatalogRepository
            );
        } catch (Exception ignored) {
            // The two original exact-ID resolvers remain available even if a
            // local catalogue update was interrupted or became incompatible.
            streamResolverRegistry = new StreamResolverRegistry(
                    highflyPremiumCatalogRepository
            );
        }
    }

    private void bindViews() {
        playerView = findViewById(R.id.player_view);
        channelOverlay = findViewById(R.id.channel_overlay);
        loadingPanel = findViewById(R.id.loading_panel);
        loadingText = findViewById(R.id.loading_text);
        clock = findViewById(R.id.clock);
        lightEpgOverlay = findViewById(R.id.light_epg_overlay);
        lightEpgChannelNumber = findViewById(R.id.light_epg_channel_number);
        lightEpgChannelName = findViewById(R.id.light_epg_channel_name);
        lightEpgGroup = findViewById(R.id.light_epg_group);
        lightEpgClock = findViewById(R.id.light_epg_clock);
        lightEpgProgress = findViewById(R.id.light_epg_progress);
        lightEpgCurrentTitle = findViewById(R.id.light_epg_current_title);
        lightEpgCurrentTime = findViewById(R.id.light_epg_current_time);
        lightEpgNextTitle = findViewById(R.id.light_epg_next_title);
        lightEpgNextTime = findViewById(R.id.light_epg_next_time);
        channelLogo = findViewById(R.id.channel_logo);
        channelLogoFallback = findViewById(R.id.channel_logo_fallback);
        channelNumber = findViewById(R.id.channel_number);
        channelName = findViewById(R.id.channel_name);
        contentTitle = findViewById(R.id.content_title);
        programmeTime = findViewById(R.id.programme_time);
        liveProgress = findViewById(R.id.live_progress);
        videoInfo = findViewById(R.id.video_info);
        codecInfo = findViewById(R.id.codec_info);
        statusDot = findViewById(R.id.status_dot);
        streamStatus = findViewById(R.id.stream_status);
    }

    private void createPlayer() {
        if (playbackBufferManager != null) playbackBufferManager.close();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        playbackBufferManager = new PlaybackBufferManager(Runtime.getRuntime().maxMemory(),
                activityManager != null && activityManager.isLowRamDevice());
        playerUsesVolumeNormalization = isVolumeNormalizationEnabled();
        VibeRenderersFactory renderersFactory = new VibeRenderersFactory(
                this,
                playerUsesVolumeNormalization
        );
        OkHttpDataSource.Factory httpDataSourceFactory =
                new OkHttpDataSource.Factory(SharedHttpClient.get()).setUserAgent(PLAYER_USER_AGENT);
        player = new ExoPlayer.Builder(
                this,
                renderersFactory
        )
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpDataSourceFactory))
                .setLoadControl(playbackBufferManager.loadControl())
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                        true
                )
                .build();
        playerView.setPlayer(player);
        playbackBufferManager.attach(player, startupMetrics);
        PlaybackDiagnosticsWorker newBitrateMeter = new PlaybackDiagnosticsWorker(
                this::requestDiagnosticsUpdate);
        playbackBitrateMeter = newBitrateMeter;
        updateDiagnosticsVisibility();
        player.addAnalyticsListener(newBitrateMeter);
        player.addAnalyticsListener(new PlaybackStartupAnalytics(startupMetrics));
        MediaCodecVideoRenderer videoRenderer = renderersFactory.getVideoRenderer();
        if (videoRenderer != null) {
            player.createMessage(videoRenderer)
                    .setType(Renderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER)
                    .setPayload(newBitrateMeter)
                    .send();
        }
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                settlePlaybackEpisode(isPlaying);
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                maybeActivatePlaybackDiagnostics();
                updateStreamStatus(playbackState);
                updateDiagnostics();
                if (playbackState == Player.STATE_READY && !loadFailed) {
                    hideLoadingState();
                } else if (playbackState == Player.STATE_BUFFERING
                        && loadingPanel != null
                        && loadingPanel.getVisibility() == View.VISIBLE) {
                    showLoadingState(getString(R.string.loading_validating_segment));
                }
            }

            @Override public void onVideoSizeChanged(VideoSize videoSize) {
                maybeActivatePlaybackDiagnostics();
                updateDiagnostics();
            }

            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                if (playbackBitrateMeter != null) {
                    playbackBitrateMeter.setMuxedStream(isMuxedAudioVideo(tracks));
                }
                maybeActivatePlaybackDiagnostics();
                updateDiagnostics();
                applySavedQualityPreference(tracks);
                applySavedSubtitlePreference(tracks);
            }

            @Override public void onCues(CueGroup cueGroup) {
                handleSubtitleCues(cueGroup);
            }

            @Override public void onPlayerError(PlaybackException error) {
                settlePlaybackEpisode(false);
                startupMetrics.failed(startupMetrics.currentId());
                setStatus("ERROR", R.color.red);
                codecInfo.setText(shortMessage(error));
                overlayAwaitingPlayback = true;
                showOverlay(true);
                cancelScheduledPlaybackRetry();
                if (isTemporaryEventChannel(playbackChannel)
                        && currentPlaybackSource != null
                        && currentPlaybackSource.isDynamicallyResolved()
                        && (isProviderRefreshError(error)
                        || PlaybackRecoveryPolicy.isRecoverable(error.errorCode))) {
                    handleTemporaryEventFailure(playbackChannel, playbackGeneration);
                    return;
                }
                if (isProviderRefreshError(error)) {
                    handleProviderAuthorizationFailure();
                    return;
                }
                MediaItem current = player == null ? null : player.getCurrentMediaItem();
                if (current != null && playbackRecoveryPolicy.tryConsumeRetry(error.errorCode)) {
                    showLoadingState(getString(R.string.loading_network_retry));
                    schedulePlaybackRetry(current.mediaId, playbackGeneration);
                    return;
                }
                // A generated host can disappear without returning an HTTP
                // authorization code. After bounded retries of the same URL,
                // discard it and resolve once more instead of remaining stuck
                // on an unreachable source forever.
                if (currentPlaybackSource != null
                        && currentPlaybackSource.isDynamicallyResolved()
                        && PlaybackRecoveryPolicy.isRecoverable(error.errorCode)) {
                    handleProviderAuthorizationFailure();
                } else {
                    hideLoadingState();
                }
            }
        });
    }

    private void refreshPlaylists(List<PlaylistSource> configuredSources) {
        List<PlaylistSource> sources = configuredSources == null
                ? Collections.emptyList()
                : new ArrayList<>(configuredSources);
        boolean premiumConfigured = isHighflyPremiumConfigured();
        String sourceSignature = playlistSourceSignature(sources);
        if (sources.isEmpty() && !premiumConfigured) {
            if (!settingsOpen) openSettings();
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
        if (!preserveCurrentPlayback) {
            playbackGeneration++;
            cancelScheduledPlaybackRetry();
            cancelPlaybackResolution();
            playbackRecoveryPolicy.reset();
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
        if (!keepCurrentUi) {
            showLoadingState(getString(
                    premiumConfigured && sources.isEmpty()
                            ? R.string.loading_premium_catalog
                            : R.string.loading_playlist
            ));
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
            startupPreferredChannelIdentity = readLastChannelIdentity();
        }

        final boolean networkAvailable = isNetworkAvailable();
        final boolean premiumIncludeEvents = premiumConfigured
                && HighflyPremiumPreferences.includeEvents(MainActivity.this);
        final Set<String> selectedPremiumEventIds = premiumConfigured
                ? HighflyPremiumPreferences.selectedEventIds(MainActivity.this)
                : Collections.emptySet();
        PlaylistRefreshState refresh = new PlaylistRefreshState(
                generation,
                sourceSignature,
                visiblePlaylists,
                sources
        );
        // A disabled Premium account must not keep a previous in-memory event
        // list visible while the ordinary sources are refreshed.
        if (!premiumConfigured || !premiumIncludeEvents) {
            refresh.latest.remove(PREMIUM_EVENT_SOURCE_POSITION);
            if (!premiumConfigured) refresh.latest.remove(PREMIUM_STABLE_SOURCE_POSITION);
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
                } catch (Exception ignored) {
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

        // Stable Premium channels arrive through the cached/public Lista 3
        // source. The protected catalog is needed only when the user opted
        // into temporary events; keep it independent from ordinary lists.
        if (premiumIncludeEvents && highflyPremiumCatalogRepository != null && networkAvailable) {
            refresh.pendingPremium = true;
            networkExecutor.submit(() -> {
                PremiumNetworkResult result;
                try {
                    HighflyPremiumCatalogRepository.PremiumPlaylists playlists =
                            highflyPremiumCatalogRepository.loadPlaylistsForDisplay(
                                    HighflyPremiumPreferences.region(MainActivity.this),
                                    true,
                                    selectedPremiumEventIds,
                                    sourceChanged
                            );
                    result = PremiumNetworkResult.success(playlists);
                } catch (Exception error) {
                    result = PremiumNetworkResult.failure(error);
                }
                PremiumNetworkResult completed = result;
                mainHandler.post(() -> applyPremiumNetworkResult(refresh, completed));
            });
        }
        finishPlaylistRefreshIfReady(refresh);
    }

    private boolean isCurrentPlaylistRefresh(PlaylistRefreshState refresh) {
        return refresh != null
                && refresh.generation == playlistGeneration
                && !isFinishing();
    }

    private void applyPlaylistNetworkResult(
            PlaylistRefreshState refresh,
            PlaylistNetworkResult result
    ) {
        if (!isCurrentPlaylistRefresh(refresh) || result == null) return;
        int position = result.source.getPosition();
        if (!refresh.completedNetworkPositions.add(position)) return;
        refresh.pendingNetwork = Math.max(0, refresh.pendingNetwork - 1);
        if (result.playlist != null) {
            refresh.latest.put(position, result.playlist);
            publishPlaylistRefresh(refresh, result.changed);
        } else {
            if (refresh.firstError == null) refresh.firstError = result.error;
            finishPlaylistRefreshIfReady(refresh);
        }
    }

    private void applyPremiumNetworkResult(
            PlaylistRefreshState refresh,
            PremiumNetworkResult result
    ) {
        if (!isCurrentPlaylistRefresh(refresh) || result == null) return;
        if (!refresh.pendingPremium) return;
        refresh.pendingPremium = false;
        if (result.playlists != null) {
            // Lista 3 is the remote public playlist already loaded above. Only
            // Lista 4 is reconstructed from the protected catalog and kept in
            // memory.
            refresh.latest.remove(PREMIUM_EVENT_SOURCE_POSITION);
            Playlist events = result.playlists.getEventPlaylist();
            if (events != null && !events.getChannels().isEmpty()) {
                refresh.latest.put(PREMIUM_EVENT_SOURCE_POSITION, events);
            }
            publishPlaylistRefresh(refresh, true);
        } else {
            if (refresh.firstError == null) refresh.firstError = result.error;
            finishPlaylistRefreshIfReady(refresh);
        }
    }

    private void publishPlaylistRefresh(PlaylistRefreshState refresh, boolean contentChanged) {
        if (!isCurrentPlaylistRefresh(refresh) || refresh.latest.isEmpty()) {
            finishPlaylistRefreshIfReady(refresh);
            return;
        }
        Map<Integer, Playlist> snapshot = new LinkedHashMap<>(refresh.latest);
        applyPlaylists(snapshot, refresh.sourceSignature, refresh.generation, contentChanged);
        loadEpgForPlaylists(snapshot, refresh.generation);
        finishPlaylistRefreshIfReady(refresh);
    }

    private void finishPlaylistRefreshIfReady(PlaylistRefreshState refresh) {
        if (!isCurrentPlaylistRefresh(refresh) || !refresh.isComplete()) return;
        if (!refresh.latest.isEmpty()) {
            hidePlaylistLoadingIfPlaybackPending();
        } else if (!channels.isEmpty()) {
            hidePlaylistLoadingIfPlaybackPending();
        } else {
            showPlaylistError(shortMessage(refresh.firstError));
        }
        startupSelectionPending = false;
    }

    private String readLastChannelIdentity() {
        return getSharedPreferences("playback_state", MODE_PRIVATE)
                .getString("last_channel", "");
    }

    private static final class PlaylistRefreshState {
        private final int generation;
        private final String sourceSignature;
        private final Map<Integer, Playlist> latest;
        private final Set<Integer> completedNetworkPositions = new HashSet<>();
        private int pendingCacheReads;
        private int pendingNetwork;
        private boolean pendingPremium;
        private Throwable firstError;

        private PlaylistRefreshState(
                int generation,
                String sourceSignature,
                Map<Integer, Playlist> visiblePlaylists,
                List<PlaylistSource> sources
        ) {
            this.generation = generation;
            this.sourceSignature = sourceSignature;
            this.latest = new LinkedHashMap<>(visiblePlaylists);
            this.pendingCacheReads = sources == null ? 0 : sources.size();
            this.pendingNetwork = 0;
        }

        private boolean isComplete() {
            return pendingCacheReads <= 0 && pendingNetwork <= 0 && !pendingPremium;
        }
    }

    private static final class PlaylistNetworkResult {
        private final PlaylistSource source;
        private final Playlist playlist;
        private final boolean changed;
        private final Throwable error;

        private PlaylistNetworkResult(
                PlaylistSource source,
                Playlist playlist,
                boolean changed,
                Throwable error
        ) {
            this.source = source;
            this.playlist = playlist;
            this.changed = changed;
            this.error = error;
        }

        private static PlaylistNetworkResult success(
                PlaylistSource source,
                PlaylistRepository.LoadResult result
        ) {
            return new PlaylistNetworkResult(
                    source,
                    result.getPlaylist(),
                    result.isChanged(),
                    null
            );
        }

        private static PlaylistNetworkResult failure(PlaylistSource source, Throwable error) {
            return new PlaylistNetworkResult(source, null, false, error);
        }
    }

    private static final class PremiumNetworkResult {
        private final HighflyPremiumCatalogRepository.PremiumPlaylists playlists;
        private final Throwable error;

        private PremiumNetworkResult(
                HighflyPremiumCatalogRepository.PremiumPlaylists playlists,
                Throwable error
        ) {
            this.playlists = playlists;
            this.error = error;
        }

        private static PremiumNetworkResult success(
                HighflyPremiumCatalogRepository.PremiumPlaylists playlists
        ) {
            return new PremiumNetworkResult(playlists, null);
        }

        private static PremiumNetworkResult failure(Throwable error) {
            return new PremiumNetworkResult(null, error);
        }
    }

    private void loadEpgForPlaylists(Map<Integer, Playlist> playlists, int generation) {
        for (Map.Entry<Integer, Playlist> entry : orderedPlaylistEntries(playlists)) {
            Playlist playlist = entry.getValue();
            if (playlist == null) continue;
            for (URI epgUri : playlist.getEpgUris()) {
                String url = epgUri.toString();
                if (!epgRequests.add(url)) continue;
                resourceCacheExecutor.submit(() -> {
                    EpgData local = null;
                    try {
                        local = epgRepository.loadCached(epgUri);
                        if (local != null) {
                            EpgData cachedData = local;
                            mainHandler.post(() -> applyEpgData(cachedData, epgUri, generation));
                        }
                    } catch (Exception ignored) {
                        // La reproducción continúa usando el grupo del canal como respaldo.
                    }
                    if (!isNetworkAvailable()) return;

                    EpgData baseline = local;
                    networkExecutor.submit(() -> {
                        try {
                            EpgRepository.LoadResult result = epgRepository.downloadIfChanged(epgUri);
                            if (result.isChanged() || baseline == null) {
                                mainHandler.post(() -> applyEpgData(
                                        result.getData(),
                                        epgUri,
                                        generation
                                ));
                            }
                        } catch (Exception ignored) {
                            // La programación cacheada permanece visible.
                        }
                    });
                });
            }
        }
    }

    private static List<Map.Entry<Integer, Playlist>> orderedPlaylistEntries(
            Map<Integer, Playlist> playlists
    ) {
        List<Map.Entry<Integer, Playlist>> entries = new ArrayList<>();
        if (playlists != null) entries.addAll(playlists.entrySet());
        Collections.sort(entries, (left, right) ->
                Integer.compare(left.getKey(), right.getKey()));
        return entries;
    }

    private void applyEpgData(EpgData data, URI epgUri, int generation) {
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
    private void scheduleEpgMerge(int generation) {
        String inputSignature = EpgData.mergeSignature(epgDataByUrl);
        if (inputSignature.equals(epgMergeInputSignature)) return;
        epgMergeInputSignature = inputSignature;
        long mergeGeneration = ++epgMergeGeneration;
        List<EpgData> snapshot = Collections.unmodifiableList(
                new ArrayList<>(epgDataByUrl.values())
        );
        networkExecutor.submit(() -> {
            EpgData merged = EpgData.merge(snapshot);
            mainHandler.post(() -> {
                if (generation != playlistGeneration
                        || mergeGeneration != epgMergeGeneration
                        || isFinishing()) return;
                epgData = merged;
                mainHandler.removeCallbacks(updateProgramme);
                updateProgramme.run();
            });
        });
    }

    private void applyPlaylists(
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
        List<Channel> sourceChannels = buildOrderedChannelList(playlistsBySource);
        resolverChannelCounts = streamResolverRegistry.countChannels(sourceChannels);
        List<Channel> enabledChannels = new ArrayList<>();
        for (Channel candidate : sourceChannels) {
            if (streamResolverRegistry.isChannelEnabled(candidate)) {
                enabledChannels.add(candidate);
            }
        }

        channels.clear();
        channels.addAll(enabledChannels);
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
                : playbackPreferences.findInitialChannelIndex(channels);
        if (startupSelectionPending) {
            if (startupPreferredChannelIdentity.isBlank()) {
                startupSelectionPending = false;
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
                || (streamChanged && !preserveResolvedPlayback)
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
            channelNumber.setText(String.format(Locale.ROOT, "%03d", channelIndex + 1));
            channelName.setText(selectedChannel.getName());
            updateProgrammeInfo();
            loadChannelLogo(selectedChannel, contentChanged);
            hideLoadingState();
        }
    }

    private static int findChannelIndexByIdentity(List<Channel> candidates, String identity) {
        if (candidates == null || identity == null || identity.isBlank()) return -1;
        for (int index = 0; index < candidates.size(); index++) {
            if (identity.equals(PlaybackPreferences.channelIdentity(candidates.get(index)))) {
                return index;
            }
        }
        return -1;
    }

    private boolean hasEstablishedPlayback() {
        return player != null
                && player.getCurrentMediaItem() != null
                && player.getPlaybackState() == Player.STATE_READY;
    }

    /**
     * Flattens the configured sources and keeps the virtual Premium lists in
     * their user-facing positions. Stable Premium entries replace an exact
     * Highfly slot when the M3U exposes the same resolver ID; newly discovered
     * stable entries are appended as Lista 3. Selected events are always
     * appended after every other source as Lista 4.
     */
    private List<Channel> buildOrderedChannelList(Map<Integer, Playlist> playlists) {
        return HighflyPremiumPlaylistMerger.merge(
                playlists,
                PREMIUM_STABLE_SOURCE_POSITION,
                PREMIUM_EVENT_SOURCE_POSITION
        );
    }

    private void showPlaylistError(String detail) {
        loadFailed = true;
        stopLoadingTextAnimation();
        loadingPanel.setVisibility(View.VISIBLE);
        String message = getString(R.string.playlist_error);
        if (detail != null && !detail.isBlank()) {
            message += " · " + SafePlaybackText.detail(detail.replace('\n', ' '));
        }
        loadingText.setText(message);
    }

    private void showLoadingState(String message) {
        if (loadingPanel == null || isFinishing()) return;
        loadingPanel.setVisibility(View.VISIBLE);
        String safeMessage = SafePlaybackText.detail(message == null ? "" : message.trim());
        boolean animate = safeMessage.endsWith("…") || safeMessage.endsWith("...");
        String base = stripTrailingEllipsis(safeMessage);
        boolean sameAnimatedMessage = animate
                && loadingMessageAnimating
                && base.equals(loadingMessageBase);
        if (sameAnimatedMessage) {
            // Several resolver stages intentionally share one visible label
            // (for example, both catalogue requests). Keep the current dot
            // phase instead of restarting it for every progress callback.
            if (!loadingAnimationScheduled) {
                loadingAnimationScheduled = true;
                mainHandler.postDelayed(animateLoadingText, 420L);
            }
            return;
        }
        if (base.equals(loadingMessageBase) && animate == loadingMessageAnimating) return;

        mainHandler.removeCallbacks(animateLoadingText);
        loadingAnimationScheduled = false;
        loadingMessageBase = base;
        loadingMessageAnimating = animate;
        loadingDotCount = 0;
        if (animate) {
            renderAnimatedLoadingText();
            loadingAnimationScheduled = true;
            mainHandler.postDelayed(animateLoadingText, 420L);
        } else {
            loadingText.setText(safeMessage);
        }
    }

    private void showResolutionProgress(ResolutionProgress progress) {
        if (progress == null || progress.getStage() == null) return;
        int current = progress.getCurrent();
        int total = progress.getTotal();
        String message;
        switch (progress.getStage()) {
            case SESSION:
                message = getString(R.string.loading_resolver_session);
                break;
            case CATALOG_REQUEST:
                message = getString(R.string.loading_resolver_catalog);
                break;
            case CATALOG_PAGE:
                message = getString(R.string.loading_resolver_catalog);
                break;
            case CATALOG_PARSED:
                message = getString(R.string.loading_resolver_catalog_parsed);
                break;
            case CATALOG_MATCHING:
                message = getString(R.string.loading_resolver_matching);
                break;
            case ALIAS_ATTEMPT:
                message = current > 0 && total > 1
                        ? getString(R.string.loading_resolver_alias, current, total)
                        : getString(R.string.loading_resolver_alias_unknown);
                break;
            case SOURCE_REQUEST:
                message = current > 0 && total > 1
                        ? getString(R.string.loading_resolver_source_request_count,
                        current,
                        total)
                        : getString(R.string.loading_resolver_source_request);
                break;
            case SOURCE_CANDIDATE:
                message = current > 0 && total > 1
                        ? getString(R.string.loading_resolver_candidate, current, total)
                        : getString(R.string.loading_resolver_candidate_unknown);
                break;
            case PAGE_REQUEST:
                message = getString(R.string.loading_resolver_page);
                break;
            case PAGE_PARSED:
                message = getString(R.string.loading_resolver_page_parsed);
                break;
            case TOKEN_REQUEST:
                message = getString(R.string.loading_resolver_token);
                break;
            case TOKEN_PARSED:
                message = getString(R.string.loading_resolver_token_parsed);
                break;
            case SOURCE_BUILDING:
                message = getString(R.string.loading_resolver_building);
                break;
            case HLS_PLAYLIST:
                message = getString(R.string.loading_hls_playlist);
                break;
            case HLS_VARIANT:
                message = getString(R.string.loading_hls_variant);
                break;
            case HLS_SEGMENT:
                message = getString(R.string.loading_validating_segment);
                break;
            case SOURCE_FOUND:
                message = getString(R.string.loading_resolver_source_ready);
                break;
            case CACHE_REUSED:
                message = getString(R.string.loading_resolver_cache_reused);
                break;
            default:
                return;
        }
        // Detailed endpoint information remains sanitized inside the progress
        // event for diagnostics, but the normal playback UI only shows the
        // short stage label. This keeps one stable, readable line and never
        // exposes a complete playback URL while a source is loading.
        showLoadingState(message);
    }

    private void stopLoadingTextAnimation() {
        mainHandler.removeCallbacks(animateLoadingText);
        loadingAnimationScheduled = false;
        loadingMessageAnimating = false;
        loadingMessageBase = "";
        loadingDotCount = 0;
    }

    /**
     * Keep the three-dot slot in the text at all times. Only the dots change
     * visibility, so the fixed stage label does not move as the animation
     * advances while the TextView remains centered.
     */
    private void renderAnimatedLoadingText() {
        if (loadingText == null) return;
        String dotSlot = "...";
        SpannableString rendered = new SpannableString(loadingMessageBase + dotSlot);
        int visibleDots = Math.min(Math.max(loadingDotCount, 0), dotSlot.length());
        if (visibleDots < dotSlot.length()) {
            rendered.setSpan(
                    new ForegroundColorSpan(Color.TRANSPARENT),
                    loadingMessageBase.length() + visibleDots,
                    loadingMessageBase.length() + dotSlot.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        loadingText.setText(rendered);
    }

    private static String stripTrailingEllipsis(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("…")) result = result.substring(0, result.length() - 1);
        while (result.endsWith("...")) result = result.substring(0, result.length() - 3);
        return result.trim();
    }

    private void hideLoadingState() {
        stopLoadingTextAnimation();
        if (loadingPanel != null) loadingPanel.setVisibility(View.GONE);
    }

    private void hidePlaylistLoadingIfPlaybackPending() {
        if (playbackChannel != null) {
            boolean playbackPending = playbackResolutionTask != null
                    || player == null
                    || player.getPlaybackState() != Player.STATE_READY;
            if (playbackPending) return;
        }
        hideLoadingState();
    }

    private void playChannel(int requestedIndex) {
        playChannel(requestedIndex, false);
    }

    private void playChannel(int requestedIndex, boolean revalidateLogo) {
        if (channels.isEmpty()) return;
        channelIndex = (requestedIndex % channels.size() + channels.size()) % channels.size();
        Channel channel = channels.get(channelIndex);
        playbackGeneration++;
        resetPlaybackBitrateMeter();
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        playbackRecoveryPolicy.reset();
        playbackChannel = channel;
        discardCurrentPlaybackSource();
        playbackRecoveryEpisode.reset();
        beginStartupMeasurement(channel, PlaybackStartupMetrics.Reason.CHANNEL);
        qualityPreferenceAppliedFor = null;
        subtitlePreferenceAppliedFor = null;
        subtitleTextObservedFor = null;

        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build());
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        playbackPreferences.rememberChannel(channel, channelIndex);

        channelNumber.setText(String.format(Locale.ROOT, "%03d", channelIndex + 1));
        channelName.setText(channel.getName());
        updateProgrammeInfo();
        videoInfo.setText("— · —");
        codecInfo.setText("— · — · —");
        setStatus("CARGANDO", R.color.amber);
        loadChannelLogo(channel, revalidateLogo);
        showLoadingState(getString(R.string.loading_preparing_channel));
        resolveAndPlay(channel, playbackGeneration);
        showOverlayForChannelStart();
    }

    private void prepareAndPlay() {
        if (player == null) return;
        player.prepare();
        player.play();
    }

    private void schedulePlaybackRetry(String expectedMediaId, long expectedGeneration) {
        cancelScheduledPlaybackRetry();
        scheduledPlaybackRetry = () -> {
            scheduledPlaybackRetry = null;
            retryCurrentPlayback(expectedMediaId, expectedGeneration);
        };
        mainHandler.postDelayed(scheduledPlaybackRetry, PLAYER_RETRY_DELAY_MS);
    }

    private void cancelScheduledPlaybackRetry() {
        if (scheduledPlaybackRetry == null) return;
        mainHandler.removeCallbacks(scheduledPlaybackRetry);
        scheduledPlaybackRetry = null;
    }

    private void cancelPlaybackResolution() {
        // A cancelled resolver may still finish its HTTP call. Incrementing
        // the request generation makes its token unusable even if its
        // callback arrives after a new channel or retry has started.
        if (playbackResolutionContext != null) {
            playbackResolutionContext.cancel();
            playbackResolutionContext = null;
        }
        if (playbackResolutionTask == null) return;
        playbackResolutionRequestId++;
        playbackResolutionTask.cancel(true);
        playbackResolutionTask = null;
        if (playbackChannel != null && streamResolverRegistry != null) {
            resolverCoordinator.invalidate(
                    playbackChannel,
                    streamResolverRegistry.find(playbackChannel)
            );
        }
    }

    private void resolveAndPlay(Channel channel, long expectedGeneration) {
        resolveAndPlay(channel, expectedGeneration, false);
    }

    private void resolveAndPlay(
            Channel channel,
            long expectedGeneration,
            boolean forceRefresh
    ) {
        if (player == null || !isCurrentPlayback(channel, expectedGeneration)) return;
        StreamResolver resolver = streamResolverRegistry.find(channel);
        if (resolver == null) {
            startupMetrics.dequeued(startupMetrics.currentId());
            startupMetrics.resolved(startupMetrics.currentId());
            showLoadingState(getString(R.string.loading_direct_source));
            startResolvedPlayback(
                    channel,
                    ResolvedPlaybackSource.direct(channel, PLAYER_USER_AGENT),
                    expectedGeneration,
                    NO_RESOLUTION_REQUEST
            );
            return;
        }

        cancelPlaybackResolution();
        // A resolver source is ephemeral. Never leave the previous token as
        // the source while a new resolver request is in flight.
        discardCurrentPlaybackSource();
        player.stop();
        player.clearMediaItems();
        long requestId = ++playbackResolutionRequestId;
        long measurementId = startupMetrics.currentId();
        ResolutionContext resolutionContext = new ResolutionContext(20_000L);
        playbackResolutionContext = resolutionContext;
        showLoadingState(getString(R.string.loading_resolver_initializing));
        ResolutionProgressListener progressListener = progress -> mainHandler.post(() -> {
            if (isCurrentPlayback(channel, expectedGeneration)
                    && requestId == playbackResolutionRequestId) {
                startupMetrics.stage(measurementId, progress.getStage());
                showResolutionProgress(progress);
            }
        });
        playbackResolutionTask = playbackExecutor.submit(() -> {
            try (ResolutionContext.Scope ignored = resolutionContext.activate()) {
                resolutionContext.check();
                startupMetrics.dequeued(measurementId);
                mainHandler.post(() -> {
                    if (isCurrentPlayback(channel, expectedGeneration)
                            && requestId == playbackResolutionRequestId) {
                        showLoadingState(getString(R.string.loading_resolver_resolving));
                    }
                });
                ResolvedPlaybackSource source = resolverCoordinator.resolve(
                        channel,
                        resolver,
                        forceRefresh,
                        progressListener
                );
                resolutionContext.check();
                if (source == null || source.isExpired(System.currentTimeMillis())) {
                    throw new java.io.IOException("La fuente venció antes de iniciar la reproducción.");
                }
                startupMetrics.resolved(measurementId);
                mainHandler.post(() -> {
                    if (!isCurrentPlayback(channel, expectedGeneration)
                            || requestId != playbackResolutionRequestId) return;
                    playbackResolutionTask = null;
                    playbackResolutionContext = null;
                    playbackManifestCache = resolutionContext.manifests();
                    startResolvedPlayback(channel, source, expectedGeneration, requestId);
                });
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) return;
                mainHandler.post(() -> {
                    if (!isCurrentPlayback(channel, expectedGeneration)
                            || requestId != playbackResolutionRequestId) return;
                    playbackResolutionTask = null;
                    playbackResolutionContext = null;
                    startupMetrics.failed(measurementId);
                    resolutionContext.cancel();
                    handleResolutionFailure(channel, resolver, expectedGeneration);
                });
            }
        });
    }

    private void handleResolutionFailure(
            Channel channel,
            StreamResolver resolver,
            long expectedGeneration
    ) {
        if (isTemporaryEventChannel(channel)) {
            handleTemporaryEventFailure(channel, expectedGeneration);
            return;
        }
        if (!playbackRecoveryEpisode.tryFallback()) {
            showPlaybackFailure();
            return;
        }
        playbackRecoveryEpisode.resolutionFailed();
        beginStartupMeasurement(channel, PlaybackStartupMetrics.Reason.FALLBACK);
        codecInfo.setText("Probando respaldo del canal");
        showLoadingState(getString(R.string.loading_fallback_source));
        startResolvedPlayback(
                channel,
                ResolvedPlaybackSource.fallback(channel, resolver.getId(), PLAYER_USER_AGENT),
                expectedGeneration,
                NO_RESOLUTION_REQUEST
        );
    }

    private void startResolvedPlayback(
            Channel channel,
            ResolvedPlaybackSource source,
            long expectedGeneration,
            long expectedResolutionRequestId
    ) {
        if (player == null || !isCurrentPlayback(channel, expectedGeneration)) return;
        if (source.isDynamicallyResolved()
                && (expectedResolutionRequestId == NO_RESOLUTION_REQUEST
                || expectedResolutionRequestId != playbackResolutionRequestId)) {
            // A resolver callback can arrive after cancellation. Never hand
            // that token to Media3, even if the channel itself is unchanged.
            return;
        }
        cancelScheduledPlaybackRetry();
        resetPlaybackBitrateMeter();
        activePlaybackSourceRequestId = source.isDynamicallyResolved()
                ? expectedResolutionRequestId
                : NO_RESOLUTION_REQUEST;
        currentPlaybackSource = source;
        if (playbackManifestCache != null) {
            ManifestHandoffCache handoff = playbackManifestCache;
            mainHandler.postDelayed(handoff::clear, ManifestHandoffCache.DEFAULT_TTL_MILLIS);
        }
        playbackRecoveryPolicy.reset();
        player.setMediaSource(mediaSourceFor(channel, source));
        showLoadingState(getString(R.string.loading_starting_playback));
        prepareAndPlay();
    }

    private void discardCurrentPlaybackSource() {
        currentPlaybackSource = null;
        if (playbackManifestCache != null) playbackManifestCache.clear();
        playbackManifestCache = null;
        activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    }

    private void resetPlaybackBitrateMeter() {
        playbackDiagnosticsActive = false;
        if (playbackBitrateMeter != null) playbackBitrateMeter.reset();
    }

    private MediaSource mediaSourceFor(Channel channel, ResolvedPlaybackSource source) {
        String userAgent = source.getUserAgent().isBlank()
                ? PLAYER_USER_AGENT
                : source.getUserAgent();
        OkHttpDataSource.Factory dataSourceFactory =
                new OkHttpDataSource.Factory(SharedHttpClient.get()).setUserAgent(userAgent);
        Map<String, String> headers = source.getRequestHeaders();
        if (!headers.isEmpty()) {
            dataSourceFactory.setDefaultRequestProperties(headers);
        }
        DataSource.Factory playbackDataSourceFactory = dataSourceFactory;
        if (playbackManifestCache != null) {
            playbackDataSourceFactory = new ManifestHandoffDataSource.Factory(
                    playbackDataSourceFactory, playbackManifestCache);
        }
        if ("meganoticias".equalsIgnoreCase(source.getResolverId())) {
            playbackDataSourceFactory = new MeganoticiasPlaylistDataSource.Factory(
                    playbackDataSourceFactory
            );
        }
        return new DefaultMediaSourceFactory(playbackDataSourceFactory)
                .setLoadErrorHandlingPolicy(new PlaybackLoadErrorPolicy(source.isDynamicallyResolved()))
                .createMediaSource(mediaItemFor(channel, source.getPlaybackUri()).buildUpon()
                        .setTag(Long.valueOf(startupMetrics.currentId())).build());
    }

    private void beginStartupMeasurement(Channel channel, PlaybackStartupMetrics.Reason reason) {
        StreamResolver resolver = channel == null || streamResolverRegistry == null
                ? null : streamResolverRegistry.find(channel);
        startupMetrics.begin(resolver == null ? "direct" : resolver.getId(), reason);
    }

    private void settlePlaybackEpisode(boolean playing) {
        if (!playbackRecoveryEpisode.onPlayingChanged(playing, System.nanoTime())) return;
        playbackRecoveryPolicy.reset();
        if (isTemporaryEventChannel(playbackChannel)) {
            temporaryEventRecoveryPolicy.markAvailable(temporaryEventId(playbackChannel));
        }
    }

    private boolean isCurrentPlayback(Channel channel, long expectedGeneration) {
        return !exiting
                && !isFinishing()
                && expectedGeneration == playbackGeneration
                && playbackChannel == channel;
    }

    private boolean isTemporaryEventChannel(Channel channel) {
        return highflyPremiumCatalogRepository != null
                && highflyPremiumCatalogRepository.isTemporaryEvent(channel);
    }

    private static String temporaryEventId(Channel channel) {
        if (channel == null || channel.getAttributes() == null) return "";
        String eventId = channel.getAttributes().get("x-highfly-premium-id");
        return eventId == null ? "" : eventId.trim();
    }

    /**
     * Temporary events never use a stale M3U fallback. Each failed resolution
     * or playback refresh gets a new Premium source, and the selected event
     * is retired after the bounded reconnection budget is exhausted.
     */
    private void handleTemporaryEventFailure(Channel channel, long expectedGeneration) {
        if (!isCurrentPlayback(channel, expectedGeneration)) return;
        String eventId = temporaryEventId(channel);
        if (eventId.isBlank()) {
            showPlaybackFailure();
            return;
        }

        if (temporaryEventRecoveryPolicy.tryConsume(eventId)) {
            beginStartupMeasurement(channel, PlaybackStartupMetrics.Reason.REFRESH);
            int attempt = temporaryEventRecoveryPolicy.attemptsFor(eventId);
            setStatus("RECONECTANDO", R.color.amber);
            codecInfo.setText(getString(
                    R.string.loading_premium_event_reconnecting,
                    attempt,
                    HighflyPremiumEventRecoveryPolicy.MAX_RECONNECTION_ATTEMPTS
            ));
            showLoadingState(getString(
                    R.string.loading_premium_event_reconnecting,
                    attempt,
                    HighflyPremiumEventRecoveryPolicy.MAX_RECONNECTION_ATTEMPTS
            ));
            cancelScheduledPlaybackRetry();
            cancelPlaybackResolution();
            discardCurrentPlaybackSource();
            StreamResolver resolver = streamResolverRegistry.find(channel);
            if (resolver != null) resolverCoordinator.invalidate(channel, resolver);
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            resolveAndPlay(channel, expectedGeneration, true);
            return;
        }

        removeUnavailableTemporaryEvent(channel, eventId, expectedGeneration);
    }

    private void removeUnavailableTemporaryEvent(
            Channel channel,
            String eventId,
            long expectedGeneration
    ) {
        if (!isCurrentPlayback(channel, expectedGeneration)) return;

        HighflyPremiumPreferences.removeSelectedEventId(this, eventId);
        temporaryEventRecoveryPolicy.clear(eventId);

        Map<Integer, Playlist> updatedPlaylists = new LinkedHashMap<>(playlistsBySource);
        Playlist eventPlaylist = updatedPlaylists.get(PREMIUM_EVENT_SOURCE_POSITION);
        if (eventPlaylist != null) {
            List<Channel> remainingEvents = new ArrayList<>();
            for (Channel event : eventPlaylist.getChannels()) {
                if (!eventId.equals(temporaryEventId(event))) remainingEvents.add(event);
            }
            if (remainingEvents.isEmpty()) {
                updatedPlaylists.remove(PREMIUM_EVENT_SOURCE_POSITION);
            } else {
                updatedPlaylists.put(
                        PREMIUM_EVENT_SOURCE_POSITION,
                        Playlist.withEpgUris(remainingEvents, eventPlaylist.getEpgUris())
                );
            }
        }

        // Invalidate a playlist refresh that may still hold the old selected
        // event set, otherwise a late callback could resurrect the channel.
        int nextPlaylistGeneration = ++playlistGeneration;
        playbackGeneration++;
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        StreamResolver resolver = streamResolverRegistry.find(channel);
        if (resolver != null) resolverCoordinator.invalidate(channel, resolver);
        playbackChannel = null;
        discardCurrentPlaybackSource();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        codecInfo.setText(R.string.highfly_premium_event_removed);

        if (updatedPlaylists.isEmpty()) {
            showPlaybackFailure();
            return;
        }
        applyPlaylists(
                updatedPlaylists,
                playlistSourceSignature(getPlaylistSources()),
                nextPlaylistGeneration,
                true
        );
    }

    private void handleProviderAuthorizationFailure() {
        if (isTemporaryEventChannel(playbackChannel)) {
            handleTemporaryEventFailure(playbackChannel, playbackGeneration);
            return;
        }
        if (playbackChannel == null || currentPlaybackSource == null
                || !currentPlaybackSource.hasResolver()) {
            showPlaybackFailure();
            return;
        }

        Channel channel = playbackChannel;
        long expectedGeneration = playbackGeneration;
        if (playbackRecoveryEpisode.tryRefresh()) {
            beginStartupMeasurement(channel, PlaybackStartupMetrics.Reason.REFRESH);
            setStatus("RENOVANDO", R.color.amber);
            codecInfo.setText("Renovando fuente");
            StreamResolver resolver = streamResolverRegistry.find(channel);
            showLoadingState(getString(R.string.loading_refreshing_source));
            // Drop the rejected URL before requesting the replacement token.
            // It must not be available to a generic retry path.
            discardCurrentPlaybackSource();
            resolverCoordinator.invalidate(channel, resolver);
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            resolveAndPlay(channel, expectedGeneration, true);
            return;
        }

        if (playbackRecoveryEpisode.tryFallback()) {
            playbackManifestCache = null;
            beginStartupMeasurement(channel, PlaybackStartupMetrics.Reason.FALLBACK);
            setStatus("RESPALDO", R.color.amber);
            codecInfo.setText("Probando respaldo del canal");
            showLoadingState(getString(R.string.loading_fallback_source));
            startResolvedPlayback(
                    channel,
                    ResolvedPlaybackSource.fallback(
                            channel,
                            currentPlaybackSource.getResolverId(),
                            PLAYER_USER_AGENT
                    ),
                    expectedGeneration,
                    NO_RESOLUTION_REQUEST
            );
            return;
        }
        showPlaybackFailure();
    }

    private void showPlaybackFailure() {
        startupMetrics.failed(startupMetrics.currentId());
        setStatus("ERROR", R.color.red);
        codecInfo.setText("Canal no disponible");
        hideLoadingState();
        overlayAwaitingPlayback = true;
        showOverlay(true);
    }

    private void retryCurrentPlayback(String expectedMediaId, long expectedGeneration) {
        if (player == null || expectedGeneration != playbackGeneration) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current == null || !expectedMediaId.equals(current.mediaId)) return;
        beginStartupMeasurement(playbackChannel, PlaybackStartupMetrics.Reason.RETRY);
        startupMetrics.dequeued(startupMetrics.currentId());
        startupMetrics.resolved(startupMetrics.currentId());
        playbackManifestCache = null;

        // Segment timeouts, transient CDN failures and rolled live segments
        // must keep the current resolved URL. Re-running the provider resolver
        // here would rebuild Media3 for an error that did not invalidate the
        // playlist or its token.
        showLoadingState(getString(R.string.loading_retrying_source));
        resetPlaybackBitrateMeter();
        player.stop();
        if (playbackChannel != null && currentPlaybackSource != null) {
            player.setMediaSource(mediaSourceFor(playbackChannel, currentPlaybackSource));
        } else {
            player.setMediaItem(current);
        }
        prepareAndPlay();
    }

    private void startPlaybackFromInput() {
        if (player == null) return;
        if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE) {
            StreamResolver resolver = streamResolverRegistry.find(playbackChannel);
            if (resolver != null) {
                if (playbackResolutionTask != null && !playbackResolutionTask.isDone()) return;
                playbackRecoveryEpisode.reset();
                beginStartupMeasurement(playbackChannel, PlaybackStartupMetrics.Reason.RETRY);
                discardCurrentPlaybackSource();
                player.stop();
                player.clearMediaItems();
                resolveAndPlay(playbackChannel, playbackGeneration);
                return;
            }
            if (player.getCurrentMediaItem() == null) return;
            playbackRecoveryPolicy.reset();
            retryCurrentPlayback(
                    player.getCurrentMediaItem().mediaId,
                    playbackGeneration
            );
            return;
        }
        player.play();
    }

    private static MediaItem mediaItemFor(Channel channel, URI playbackUri) {
        Uri uri = Uri.parse(playbackUri.toString());
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(uri)
                .setMediaId(PlaybackPreferences.channelIdentity(channel));
        if (isHlsUri(uri)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        return builder.build();
    }

    private static boolean isHlsUri(Uri uri) {
        String path = uri.getPath();
        return path != null && path.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private void updateProgrammeInfo() {
        if (channels.isEmpty() || channelIndex < 0 || channelIndex >= channels.size()) return;
        Channel channel = channels.get(channelIndex);
        long now = System.currentTimeMillis();
        EpgProgramme programme = epgData.findCurrent(channel.getTvgId(), now);

        if (programme == null) {
            contentTitle.setText(channel.getGroup().isBlank()
                    ? getString(R.string.live_content)
                    : channel.getGroup());
            programmeTime.setVisibility(View.GONE);
            liveProgress.setIndeterminate(true);
            updateLightEpgInfo();
            return;
        }

        contentTitle.setText(programme.getTitle());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String timeRange = timeFormat.format(new Date(programme.getStartMillis()))
                + " — "
                + timeFormat.format(new Date(programme.getStopMillis()));
        programmeTime.setText(timeRange);
        programmeTime.setVisibility(View.VISIBLE);

        long duration = programme.getStopMillis() - programme.getStartMillis();
        int progress = duration <= 0 ? 0 : (int) Math.max(0, Math.min(1000,
                ((now - programme.getStartMillis()) * 1000L) / duration));
        liveProgress.setIndeterminate(false);
        liveProgress.setMax(1000);
        liveProgress.setProgress(progress);
        updateLightEpgInfo();
    }

    private void updateLightEpgInfo() {
        if (lightEpgOverlay == null
                || lightEpgOverlay.getVisibility() != View.VISIBLE
                || channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return;

        Channel channel = channels.get(channelIndex);
        long now = System.currentTimeMillis();
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        EpgProgramme current = epgData.findCurrent(channel.getTvgId(), now);
        EpgProgramme next = epgData.findNext(channel.getTvgId(), now);

        lightEpgChannelNumber.setText(String.format(Locale.ROOT, "%03d", channelIndex + 1));
        lightEpgChannelName.setText(channel.getName());
        lightEpgGroup.setText(channel.getGroup().isBlank()
                ? getString(R.string.live_content)
                : channel.getGroup());
        lightEpgClock.setText(timeFormat.format(new Date(now)));

        if (current == null) {
            lightEpgCurrentTitle.setText(channel.getGroup().isBlank()
                    ? getString(R.string.live_content)
                    : channel.getGroup());
            lightEpgCurrentTime.setText(R.string.epg_no_information);
            lightEpgProgress.setIndeterminate(true);
        } else {
            lightEpgCurrentTitle.setText(current.getTitle());
            lightEpgCurrentTime.setText(formatProgrammeRange(current, timeFormat));
            long duration = current.getStopMillis() - current.getStartMillis();
            int progress = duration <= 0 ? 0 : (int) Math.max(0, Math.min(1000,
                    ((now - current.getStartMillis()) * 1000L) / duration));
            lightEpgProgress.setIndeterminate(false);
            lightEpgProgress.setMax(1000);
            lightEpgProgress.setProgress(progress);
        }

        if (next == null) {
            lightEpgNextTitle.setText(R.string.epg_no_next_programme);
            lightEpgNextTime.setText("");
        } else {
            lightEpgNextTitle.setText(next.getTitle());
            lightEpgNextTime.setText(formatProgrammeRange(next, timeFormat));
        }
    }

    private static String formatProgrammeRange(
            EpgProgramme programme,
            SimpleDateFormat timeFormat
    ) {
        return timeFormat.format(new Date(programme.getStartMillis()))
                + " — "
                + timeFormat.format(new Date(programme.getStopMillis()));
    }

    private void loadChannelLogo(Channel channel, boolean revalidate) {
        URI logoUri = channel.getLogoUri();
        String fallback = initials(channel.getName());
        String expectedIdentity = PlaybackPreferences.channelIdentity(channel);
        long requestGeneration = ++logoRequestGeneration;
        if (!expectedIdentity.equals(displayedLogoIdentity)) {
            channelLogo.setImageDrawable(null);
            channelLogo.setVisibility(View.GONE);
            channelLogoFallback.setText(fallback);
            channelLogoFallback.setVisibility(View.VISIBLE);
            displayedLogoIdentity = "";
        }
        if (logoUri == null || !("http".equalsIgnoreCase(logoUri.getScheme()) || "https".equalsIgnoreCase(logoUri.getScheme()))) {
            return;
        }

        int expectedIndex = channelIndex;
        int targetWidthPx = channelLogo.getWidth() > 0
                ? channelLogo.getWidth()
                : dpToPx(78);
        int targetHeightPx = channelLogo.getHeight() > 0
                ? channelLogo.getHeight()
                : dpToPx(54);
        boolean shouldRevalidate = revalidate
                || logoRevalidatedThisSession.add(logoUri.toString());
        Future<?> previousTask = logoRequestTask;
        if (previousTask != null) previousTask.cancel(true);
        logoRequestTask = logoCacheExecutor.submit(() -> {
            android.graphics.Bitmap cached = channelLogoCache.loadCached(
                    logoUri,
                    targetWidthPx,
                    targetHeightPx
            );
            if (cached != null) {
                mainHandler.post(() -> showChannelLogo(
                        cached,
                        expectedIndex,
                        expectedIdentity,
                        requestGeneration
                ));
            }

            if (cached != null && !shouldRevalidate) return;
            try {
                ChannelLogoCache.RefreshResult refreshed = channelLogoCache.refreshIfChanged(
                        logoUri,
                        targetWidthPx,
                        targetHeightPx
                );
                if (cached != null && !refreshed.isChanged()) return;
                mainHandler.post(() -> showChannelLogo(
                        refreshed.getBitmap(),
                        expectedIndex,
                        expectedIdentity,
                        requestGeneration
                ));
            } catch (Exception ignored) {
                // El logo en caché ya mostrado permanece si falla la actualización.
            }
        });
    }

    private void showChannelLogo(
            android.graphics.Bitmap bitmap,
            int expectedIndex,
            String expectedIdentity,
            long requestGeneration
    ) {
        if (requestGeneration != logoRequestGeneration
                || !isCurrentLogo(expectedIndex, expectedIdentity)
                || isFinishing()) return;
        channelLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        channelLogo.setImageBitmap(bitmap);
        channelLogo.setVisibility(View.VISIBLE);
        channelLogoFallback.setVisibility(View.GONE);
        displayedLogoIdentity = expectedIdentity;
    }

    private boolean isCurrentLogo(int expectedIndex, String expectedIdentity) {
        if (expectedIndex != channelIndex || expectedIndex < 0 || expectedIndex >= channels.size()) {
            return false;
        }
        return expectedIdentity.equals(
                PlaybackPreferences.channelIdentity(channels.get(expectedIndex))
        );
    }

    private int dpToPx(int dp) {
        return Math.max(1, Math.round(dp * getResources().getDisplayMetrics().density));
    }

    private void updateStreamStatus(int state) {
        if (state == Player.STATE_READY) {
            setStatus("ESTABLE", R.color.green);
            if (!loadFailed) hideLoadingState();
            if (overlayAwaitingPlayback) {
                overlayAwaitingPlayback = false;
                showOverlay(false);
            }
        } else if (state == Player.STATE_BUFFERING) {
            setStatus("CARGANDO", R.color.amber);
        } else if (state == Player.STATE_ENDED) {
            setStatus("FINALIZADO", R.color.muted);
        }
    }

    private void maybeActivatePlaybackDiagnostics() {
        if (playbackDiagnosticsActive || player == null || playbackBitrateMeter == null) {
            return;
        }
        if (player.getPlaybackState() == Player.STATE_READY
                && playbackBitrateMeter.snapshot().hasRenderedVideoFrame) {
            playbackDiagnosticsActive = true;
        }
    }

    /** Called by the diagnostics worker; never touches Player or View objects on that thread. */
    private void requestDiagnosticsUpdate() {
        if (!diagnosticsUpdateQueued.compareAndSet(false, true)) return;
        if (!mainHandler.post(applyMeasuredDiagnostics)) diagnosticsUpdateQueued.set(false);
    }

    private void updateDiagnosticsVisibility() {
        if (playbackBitrateMeter != null) {
            playbackBitrateMeter.setNotificationsEnabled(!resourcesReleased && !settingsOpen
                    && hasWindowFocus() && channelOverlay != null && channelOverlay.isShown());
        }
    }

    private void updateDiagnostics() {
        if (player == null || settingsOpen || !hasWindowFocus()
                || channelOverlay == null || !channelOverlay.isShown()) return;
        // Showing the OSD must not replace a playback error with the last successful samples.
        if (player.getPlayerError() != null || loadFailed) return;
        if (!playbackDiagnosticsActive) {
            // Track metadata can be available while Media3 is still opening
            // the stream. Do not expose a partial diagnostic row at that
            // point; the first rendered frame is the first reliable playback
            // boundary for showing codec and FPS information.
            setTextIfChanged(videoInfo, "— · —");
            setTextIfChanged(codecInfo, "— · — · —");
            return;
        }
        Format video = player.getVideoFormat();
        Format audio = player.getAudioFormat();
        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();

        String resolution = video != null && video.width > 0 && video.height > 0
                ? video.width + " × " + video.height
                : "—";
        float frameRate = measurements.displayFrameRate;
        String fps = frameRate > 0 ? trimDecimal(frameRate) + " FPS" : "— FPS";
        setTextIfChanged(videoInfo, resolution + " · " + fps);

        String videoCodec = codecName(video == null ? null : video.sampleMimeType);
        String audioCodec = codecName(audio == null ? null : audio.sampleMimeType);
        boolean isMuxedStream = measurements.muxedStream;
        long measuredBitrate = isMuxedStream ? measurements.streamBitrate : measurements.videoBitrate;
        int declaredBitrate = isMuxedStream
                ? declaredStreamBitrate(video, audio)
                : declaredBitrate(video);
        long displayBitrate = measuredBitrate > 0 ? measuredBitrate : declaredBitrate;
        setTextIfChanged(codecInfo, videoCodec + " · " + audioCodec + " · "
                + compactBitrate(displayBitrate));
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static String compactBitrate(long bitsPerSecond) {
        return bitsPerSecond > 0 ? formatBitrate(bitsPerSecond) : "—";
    }

    private static int declaredBitrate(Format format) {
        if (format == null) return Format.NO_VALUE;
        if (format.averageBitrate > 0) return format.averageBitrate;
        return format.peakBitrate > 0 ? format.peakBitrate : Format.NO_VALUE;
    }

    private static boolean isMuxedAudioVideo(Tracks tracks) {
        Set<String> selectedVideoGroupIds = new HashSet<>();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO && group.isSelected()) {
                selectedVideoGroupIds.add(group.getMediaTrackGroup().id);
            }
        }
        if (selectedVideoGroupIds.isEmpty()) return false;

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO || !group.isSelected()) continue;
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                if (!group.isTrackSelected(trackIndex)) continue;
                Format audioFormat = group.getTrackFormat(trackIndex);
                if (audioFormat.primaryTrackGroupId != null
                        && selectedVideoGroupIds.contains(audioFormat.primaryTrackGroupId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int declaredStreamBitrate(Format video, Format audio) {
        int videoBitrate = declaredBitrate(video);
        return videoBitrate > 0 ? videoBitrate : declaredBitrate(audio);
    }

    private static String formatBitrate(long bitsPerSecond) {
        if (bitsPerSecond >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000d);
        }
        return String.format(Locale.ROOT, "%.0f kbps", bitsPerSecond / 1_000d);
    }

    private void applySavedQualityPreference(Tracks tracks) {
        if (player == null || channels.isEmpty()
                || channelIndex < 0 || channelIndex >= channels.size()) return;
        Channel channel = channels.get(channelIndex);
        String channelIdentity = PlaybackPreferences.channelIdentity(channel);
        MediaItem mediaItem = player.getCurrentMediaItem();
        if (mediaItem == null || !channelIdentity.equals(mediaItem.mediaId)
                || channelIdentity.equals(qualityPreferenceAppliedFor)) return;

        PlaybackPreferences.QualityPreference preference =
                playbackPreferences.getQuality(channel);
        if (preference == null) {
            if (playbackPreferences.isAutomaticQuality(channel)) {
                qualityPreferenceAppliedFor = channelIdentity;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build());
                return;
            }
            List<VideoTrackOption> options = collectVideoTrackOptions(tracks);
            if (!options.isEmpty()) {
                // No stored preference means the default is the best available
                // bitrate. Adaptive quality remains available when the user
                // explicitly selects "Automático" in Playback settings.
                applyFixedQuality(channel, options.get(0), false);
            } else {
                qualityPreferenceAppliedFor = channelIdentity;
            }
            return;
        }

        VideoTrackOption option = findClosestQuality(
                collectVideoTrackOptions(tracks),
                preference
        );
        if (option != null) applyFixedQuality(channel, option, false);
    }

    private void applySavedSubtitlePreference(Tracks tracks) {
        if (player == null || channels.isEmpty()
                || channelIndex < 0 || channelIndex >= channels.size()
                || !hasSupportedTextTrack(tracks)) return;

        Channel channel = channels.get(channelIndex);
        String channelIdentity = PlaybackPreferences.channelIdentity(channel);
        MediaItem mediaItem = player.getCurrentMediaItem();
        if (mediaItem == null || !channelIdentity.equals(mediaItem.mediaId)
                || channelIdentity.equals(subtitlePreferenceAppliedFor)) return;

        // A manifest can advertise a text group without ever delivering a real
        // subtitle cue. Keep text enabled while probing the stream so a saved
        // "off" preference cannot prevent onCues() from proving availability.
        boolean textObserved = channelIdentity.equals(subtitleTextObservedFor);
        subtitlePreferenceAppliedFor = channelIdentity;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(
                        C.TRACK_TYPE_TEXT,
                        textObserved && !playbackPreferences.getSubtitles(channel)
                )
                .build());
    }

    private void handleSubtitleCues(CueGroup cueGroup) {
        if (!hasNonBlankTextCue(cueGroup)
                || player == null
                || channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return;

        MediaItem mediaItem = player.getCurrentMediaItem();
        if (mediaItem == null) return;

        String channelIdentity = PlaybackPreferences.channelIdentity(
                channels.get(channelIndex)
        );
        if (!channelIdentity.equals(mediaItem.mediaId)) return;

        subtitleTextObservedFor = channelIdentity;
        subtitlePreferenceAppliedFor = null;
        applySavedSubtitlePreference(player.getCurrentTracks());
    }

    private static boolean hasNonBlankTextCue(CueGroup cueGroup) {
        for (Cue cue : cueGroup.cues) {
            if (cue.text != null && !cue.text.toString().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasObservedSubtitleText(Channel channel) {
        return PlaybackPreferences.channelIdentity(channel).equals(subtitleTextObservedFor);
    }

    private static boolean hasSupportedTextTrack(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT
                    && group.isSupported()
                    && group.length > 0) {
                return true;
            }
        }
        return false;
    }

    private void useAutomaticQuality(Channel channel) {
        playbackPreferences.useAutomaticQuality(channel);
        qualityPreferenceAppliedFor = PlaybackPreferences.channelIdentity(channel);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build());
    }

    private void applyFixedQuality(
            Channel channel,
            VideoTrackOption option,
            boolean remember
    ) {
        if (remember) {
            playbackPreferences.rememberQuality(
                    channel,
                    option.bitrate,
                    option.width,
                    option.height
            );
        }
        qualityPreferenceAppliedFor = PlaybackPreferences.channelIdentity(channel);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setOverrideForType(new TrackSelectionOverride(
                        option.group.getMediaTrackGroup(),
                        option.trackIndex
                ))
                .build());
    }

    private static List<VideoTrackOption> collectVideoTrackOptions(Tracks tracks) {
        Tracks.Group selectedGroup = null;
        Tracks.Group fallbackGroup = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isSupported()) continue;
            if (fallbackGroup == null) fallbackGroup = group;
            if (group.isSelected()) {
                selectedGroup = group;
                break;
            }
        }

        Tracks.Group group = selectedGroup == null ? fallbackGroup : selectedGroup;
        List<VideoTrackOption> result = new ArrayList<>();
        if (group == null) return result;
        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
            if (!group.isTrackSupported(trackIndex)) continue;
            result.add(new VideoTrackOption(
                    group,
                    trackIndex,
                    group.getTrackFormat(trackIndex)
            ));
        }
        Collections.sort(result, new Comparator<VideoTrackOption>() {
            @Override
            public int compare(VideoTrackOption left, VideoTrackOption right) {
                int bitrateOrder = Integer.compare(right.bitrate, left.bitrate);
                return bitrateOrder != 0
                        ? bitrateOrder
                        : Integer.compare(right.height, left.height);
            }
        });
        return result;
    }

    private static VideoTrackOption findClosestQuality(
            List<VideoTrackOption> options,
            PlaybackPreferences.QualityPreference preference
    ) {
        VideoTrackOption closest = null;
        long closestScore = Long.MAX_VALUE;
        for (VideoTrackOption option : options) {
            long score;
            if (preference.bitrate > 0 && option.bitrate > 0) {
                score = Math.abs((long) preference.bitrate - option.bitrate) * 1_000L;
                score += Math.abs(preference.height - option.height);
            } else {
                score = Math.abs((long) preference.height - option.height) * 1_000_000L;
                score += Math.abs(preference.width - option.width);
            }
            if (score < closestScore) {
                closest = option;
                closestScore = score;
            }
        }
        return closest;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text, int colorResource) {
        streamStatus.setText(text);
        statusDot.setTextColor(getColor(colorResource));
    }

    private void showOverlay(boolean keepVisible) {
        hideLightEpg.run();
        mainHandler.removeCallbacks(hideLightEpg);
        channelOverlay.setVisibility(View.VISIBLE);
        clock.setVisibility(View.VISIBLE);
        updateDiagnosticsVisibility();
        maybeActivatePlaybackDiagnostics();
        updateDiagnostics();
        mainHandler.removeCallbacks(hideOverlay);
        if (!keepVisible) {
            mainHandler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS);
        }
    }

    private void showOverlayForChannelStart() {
        overlayAwaitingPlayback = true;
        showOverlay(true);
    }

    private void showLightEpg() {
        if (channels.isEmpty() || channelIndex < 0 || channelIndex >= channels.size()) return;
        mainHandler.removeCallbacks(hideOverlay);
        mainHandler.removeCallbacks(hideLightEpg);
        hideOverlay.run();
        updateLightEpgInfo();
        lightEpgOverlay.setVisibility(View.VISIBLE);
        updateLightEpgInfo();
        mainHandler.postDelayed(hideLightEpg, LIGHT_EPG_TIMEOUT_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isChannelNavigationKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // Android TV remotes emit repeated ACTION_DOWN events while
                // a channel key is held. Handle every repeat so holding
                // Channel +/− (and the existing D-pad aliases) scrolls
                // through channels continuously.
                playChannel(channelIndex + channelNavigationDelta(keyCode));
            }
            // Consume ACTION_UP as well so the focused player/view cannot
            // reinterpret the release as another navigation action.
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                showLightEpg();
            }
            return true;
        }

        // Keep the right arrow inert during playback. The settings tabs are
        // navigated only after the settings screen has been opened explicitly.
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
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

    private static boolean isChannelNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN;
    }

    private int channelNavigationDelta(int keyCode) {
        boolean inverted = isChannelNavigationInverted();
        boolean movesUp = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP;
        if (movesUp) return inverted ? 1 : -1;
        return inverted ? -1 : 1;
    }

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBackAction);
        }
    }

    private void handleBackAction() {
        if (lightEpgOverlay.getVisibility() == View.VISIBLE) {
            mainHandler.removeCallbacks(hideLightEpg);
            hideLightEpg.run();
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

    private void showExitDialog() {
        if (exiting || (exitDialog != null && exitDialog.isShowing())) return;

        exitDialog = new Dialog(this);
        exitDialog.setContentView(R.layout.dialog_exit);
        exitDialog.setCanceledOnTouchOutside(false);

        Button stayButton = exitDialog.findViewById(R.id.stay_button);
        Button exitButton = exitDialog.findViewById(R.id.exit_button);
        stayButton.setOnClickListener(view -> exitDialog.dismiss());
        exitButton.setOnClickListener(view -> exitApplication());
        exitDialog.setOnShowListener(dialog -> exitButton.requestFocus());
        exitDialog.setOnDismissListener(dialog -> {
            exitDialog = null;
            if (!exiting) enterImmersiveMode();
        });

        Window window = exitDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.68f;
            window.setAttributes(attributes);
        }
        exitDialog.show();
    }

    private void exitApplication() {
        if (exiting) return;
        exiting = true;
        releaseAppResources();
        closeApplicationTasksAndProcess();
    }

    private void releaseAppResources() {
        if (resourcesReleased) return;
        resourcesReleased = true;
        if (playbackBufferManager != null) {
            playbackBufferManager.close();
            playbackBufferManager = null;
        }
        startupMetrics.finish();
        playlistGeneration++;
        playbackGeneration++;
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        resolverCoordinator.clear();
        if (streamResolverRegistry != null) streamResolverRegistry.clearSensitiveState();
        if (highflyPremiumCatalogRepository != null) {
            highflyPremiumCatalogRepository.clearSession();
        }
        if (highflyPremiumCredentialStore != null) {
            highflyPremiumCredentialStore.clearSession();
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (contentTitle != null) contentTitle.release();

        if (exitDialog != null) {
            exitDialog.dismiss();
            exitDialog = null;
        }
        if (appUpdater != null) {
            appUpdater.destroy();
            appUpdater = null;
        }
        networkExecutor.shutdownNow();
        playbackExecutor.shutdownNow();
        logoCacheExecutor.shutdownNow();
        resourceCacheExecutor.shutdownNow();

        if (playbackBitrateMeter != null) {
            playbackBitrateMeter.close();
            playbackBitrateMeter = null;
        }

        playbackChannel = null;
        discardCurrentPlaybackSource();
        channels.clear();
        epgData = EpgData.empty();
        qualityPreferenceAppliedFor = null;
        subtitlePreferenceAppliedFor = null;
        subtitleTextObservedFor = null;

        if (channelLogo != null) {
            channelLogo.setImageDrawable(null);
        }
        displayedLogoIdentity = "";
        logoRevalidatedThisSession.clear();
        if (channelLogoCache != null) {
            channelLogoCache.clearMemory();
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
    }

    private void closeApplicationTasksAndProcess() {
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

    private Intent createSettingsIntent(int initialTab) {
        Intent intent = new Intent(this, SettingsActivity.class);
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
        intent.putExtra(
                        SettingsActivity.EXTRA_RESOLVER_CATALOG_VERSION,
                        streamResolverRegistry.getCatalogVersion()
                )
                .putStringArrayListExtra(SettingsActivity.EXTRA_RESOLVER_IDS, resolverIds)
                .putIntegerArrayListExtra(SettingsActivity.EXTRA_RESOLVER_COUNTS, resolverCounts);
        if (channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return intent;

        Channel channel = channels.get(channelIndex);
        intent.putExtra(SettingsActivity.EXTRA_CHANNEL_INDEX, channelIndex)
                .putExtra(SettingsActivity.EXTRA_CHANNEL_TVG_ID, channel.getTvgId())
                .putExtra(SettingsActivity.EXTRA_CHANNEL_NAME, channel.getName());

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
        return intent;
    }

    private void openSettings() {
        openSettings(-1);
    }

    private void openSettings(int initialTab) {
        if (settingsOpen) return;
        resolverSettingsSnapshotBeforeSettings = resolverSettingsSnapshot();
        playlistSourcesSnapshotBeforeSettings = playlistSourceSignature(getPlaylistSources());
        settingsOpen = true;
        startActivityForResult(createSettingsIntent(initialTab), SETTINGS_REQUEST);
    }

    private void applyPlaybackSettingsResult(Intent data) {
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
        if (expectedTvgId != null && !expectedTvgId.isBlank()
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
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (appUpdater != null && appUpdater.onActivityResult(requestCode)) return;
        if (requestCode == SETTINGS_REQUEST) {
            settingsOpen = false;
            String resolverSnapshotBefore = resolverSettingsSnapshotBeforeSettings;
            String playlistSnapshotBefore = playlistSourcesSnapshotBeforeSettings;
            resolverSettingsSnapshotBeforeSettings = "";
            playlistSourcesSnapshotBeforeSettings = "";
            List<PlaylistSource> sources = getPlaylistSources();
            if (resultCode == RESULT_OK
                    && (!sources.isEmpty() || isHighflyPremiumConfigured())) {
                applyPlaybackSettingsResult(data);
                reloadResolverRegistry();
                boolean resolverConfigurationChanged = resolverSnapshotBefore.isBlank()
                        || !resolverSnapshotBefore.equals(resolverSettingsSnapshot());
                boolean playlistConfigurationChanged = playlistSnapshotBefore.isBlank()
                        || !playlistSnapshotBefore.equals(playlistSourceSignature(sources));
                if (resolverConfigurationChanged || playlistConfigurationChanged) {
                    resolverCoordinator.clear();
                }
                temporaryEventRecoveryPolicy.clearAll();
                if (playerUsesVolumeNormalization != isVolumeNormalizationEnabled()) {
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
                    epgData = EpgData.empty();
                }
                refreshAfterSettings = true;
            } else if (sources.isEmpty() && !isHighflyPremiumConfigured()) {
                openSettings();
            }
        }
    }

    private List<PlaylistSource> getPlaylistSources() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        String url1 = prefs.getString(SettingsActivity.KEY_PLAYLIST_URL, "");
        String url2 = prefs.getString(SettingsActivity.KEY_PLAYLIST_URL_2, "");
        boolean enabled1 = prefs.contains(SettingsActivity.KEY_PLAYLIST_ENABLED)
                ? prefs.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, true)
                : url1 != null && !url1.isBlank();
        boolean enabled2 = prefs.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false);
        List<PlaylistSource> sources = new ArrayList<>();
        if (enabled1 && url1 != null && !url1.trim().isEmpty()) {
            sources.add(new PlaylistSource(1, url1));
        }
        if (enabled2 && url2 != null && !url2.trim().isEmpty()) {
            sources.add(new PlaylistSource(2, url2));
        }
        if (isHighflyPremiumConfigured()) {
            sources.add(new PlaylistSource(
                    PREMIUM_STABLE_SOURCE_POSITION,
                    HighflyPremiumPreferences.STABLE_PLAYLIST_URL
            ));
        }
        return sources;
    }

    private boolean isHighflyPremiumConfigured() {
        return highflyPremiumCredentialStore != null
                && highflyPremiumCredentialStore.hasCredential()
                && HighflyPremiumPreferences.isEnabled(this);
    }

    private String playlistSourceSignature(List<PlaylistSource> sources) {
        String m3uSignature = PlaylistSource.signature(sources);
        String premiumSignature = highflyPremiumCredentialStore == null
                ? "premium=unavailable"
                : HighflyPremiumPreferences.sourceSignature(
                        this,
                        highflyPremiumCredentialStore
                );
        return (m3uSignature.isBlank() ? "sources=none" : m3uSignature)
                + "|premium=" + premiumSignature;
    }

    private String resolverSettingsSnapshot() {
        if (streamResolverRegistry == null) return "resolver=unavailable";
        StringBuilder snapshot = new StringBuilder(
                streamResolverRegistry.getCatalogVersion()
        );
        if (resolverPreferences != null) {
            snapshot.append("|tvvoo=")
                    .append(resolverPreferences.getTvVooResolutionMode());
        }
        for (ResolverDefinition definition : streamResolverRegistry.getDefinitions()) {
            snapshot.append('|')
                    .append(definition.getId())
                    .append(':')
                    .append(definition.getEngine())
                    .append('=')
                    .append(resolverPreferences == null
                            || resolverPreferences.isEnabled(definition));
        }
        return snapshot.toString();
    }

    private boolean isChannelNavigationInverted() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_INVERT_CHANNEL_KEYS, false);
    }

    private boolean isVolumeNormalizationEnabled() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_NORMALIZE_VOLUME, false);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return true;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "TV";
        StringBuilder result = new StringBuilder(2);
        for (String word : name.trim().split("\\s+")) {
            if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0)));
            if (result.length() == 2) break;
        }
        return result.length() == 0 ? "TV" : result.toString();
    }

    private static String codecName(String mimeType) {
        if (mimeType == null) return "—";
        return switch (mimeType) {
            case MimeTypes.VIDEO_H264 -> "H.264";
            case MimeTypes.VIDEO_H265 -> "H.265";
            case MimeTypes.VIDEO_AV1 -> "AV1";
            case MimeTypes.VIDEO_VP9 -> "VP9";
            case MimeTypes.AUDIO_AAC -> "AAC";
            case MimeTypes.AUDIO_AC3 -> "AC-3";
            case MimeTypes.AUDIO_E_AC3 -> "E-AC-3";
            case MimeTypes.AUDIO_OPUS -> "Opus";
            default -> mimeType.substring(mimeType.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        };
    }

    private static String trimDecimal(float value) {
        return value == Math.round(value)
                ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String shortMessage(Throwable error) {
        int responseCode = httpResponseCode(error);
        if (responseCode == 401 || responseCode == 403) {
            return "Autorización rechazada.";
        }
        if (responseCode == 404 || responseCode == 410) {
            return ResolvedSourceRefreshPolicy.isManifest(failedRequestUri(error))
                    ? "Fuente caducada."
                    : "Segmento temporal no disponible.";
        }
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return "Error desconocido.";
        return SafePlaybackText.detail(message);
    }

    private boolean isProviderRefreshError(PlaybackException error) {
        if (currentPlaybackSource == null
                || !currentPlaybackSource.hasResolver()
                || !currentPlaybackSource.isDynamicallyResolved()) {
            return false;
        }
        if (currentPlaybackSource.isDynamicallyResolved()
                && activePlaybackSourceRequestId != playbackResolutionRequestId) {
            // Ignore an authorization error from a Media3 item that was
            // superseded while its callback was still being delivered.
            return false;
        }
        int responseCode = httpResponseCode(error);
        return ResolvedSourceRefreshPolicy.shouldRefresh(
                responseCode,
                failedRequestUri(error),
                error.errorCode
        );
    }

    private static URI failedRequestUri(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 20) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                Uri uri = ((HttpDataSource.InvalidResponseCodeException) current)
                        .dataSpec.uri;
                try {
                    return URI.create(uri.toString());
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private static int httpResponseCode(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 20) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) current).responseCode;
            }
            current = current.getCause();
        }
        return -1;
    }

    private void enterImmersiveMode() {
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
        if (!settingsOpen && !refreshAfterSettings) {
            List<PlaylistSource> sources = getPlaylistSources();
            if (sources.isEmpty() && !isHighflyPremiumConfigured()) {
                openSettings();
            } else {
                refreshPlaylists(sources);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (appUpdater != null) appUpdater.onHostResume();
        if (refreshAfterSettings) {
            refreshAfterSettings = false;
            refreshPlaylists(getPlaylistSources());
        }
        startPlaybackFromInput();
        updateDiagnosticsVisibility();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
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
        mainHandler.removeCallbacks(hideLightEpg);
        hideLightEpg.run();
        if (!settingsOpen) {
            cancelScheduledPlaybackRetry();
            if (playbackChannel != null && streamResolverRegistry.find(playbackChannel) != null) {
                // Leaving the activity ends this resolver session. Resuming it
                // will obtain a new token instead of reviving a stale MediaItem.
                cancelPlaybackResolution();
                discardCurrentPlaybackSource();
                if (player != null) {
                    player.stop();
                    player.clearMediaItems();
                }
            } else if (player != null) {
                player.pause();
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseAppResources();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (playbackBufferManager != null) playbackBufferManager.onMemoryPressure(level);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (playbackBufferManager != null) playbackBufferManager.onMemoryPressure(80);
    }

    private static final class VideoTrackOption {
        final Tracks.Group group;
        final int trackIndex;
        final int bitrate;
        final int width;
        final int height;

        VideoTrackOption(Tracks.Group group, int trackIndex, Format format) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.bitrate = format.averageBitrate > 0
                    ? format.averageBitrate
                    : Math.max(format.peakBitrate, 0);
            this.width = Math.max(format.width, 0);
            this.height = Math.max(format.height, 0);
        }

        String label() {
            List<String> parts = new ArrayList<>();
            if (height > 0) parts.add(height + "p");
            if (bitrate > 0) {
                parts.add(String.format(
                        Locale.ROOT,
                        "%.1f Mbps",
                        bitrate / 1_000_000f
                ));
            }
            if (parts.isEmpty()) return "Pista " + (trackIndex + 1);
            return parts.size() == 1 ? parts.get(0) : parts.get(0) + " · " + parts.get(1);
        }
    }
}
