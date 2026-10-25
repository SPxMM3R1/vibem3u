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

/**
 * Reproducción: reproductor, apertura de canales, resolución, recuperación, calidad y pistas.
 * Capa de MainActivity (separada en la 0.6.03; el código se movió tal cual).
 */
@UnstableApi
abstract class MainPlayback extends MainState {
    void createPlayer() {
        if (playbackBufferManager != null) playbackBufferManager.close();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        playbackBufferManager = new PlaybackBufferManager(Runtime.getRuntime().maxMemory(),
                activityManager != null && activityManager.isLowRamDevice());
        playerUsesVolumeNormalization = isVolumeNormalizationEnabled();
        playerUsesSoftwareMp2 = isSoftwareMp2Enabled();
        VibeRenderersFactory renderersFactory = new VibeRenderersFactory(
                this,
                playerUsesVolumeNormalization,
                playerUsesSoftwareMp2
        );
        OkHttpDataSource.Factory httpDataSourceFactory =
                new OkHttpDataSource.Factory(SharedHttpClient.get()).setUserAgent(PLAYER_USER_AGENT)
                        .setTransferListener(mediaTransferProgress);
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
                if (isPlaying) maybeSchedulePlaybackSourceStability();
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                maybeActivatePlaybackDiagnostics();
                updateStreamStatus(playbackState);
                updateDiagnostics();
                if (playbackState == Player.STATE_READY && !loadFailed) {
                    if (hasRenderedVideoFrame()) {
                        markPlaybackStarted();
                        playbackLoadingSinceElapsedRealtime = -1L;
                        maybeSchedulePlaybackSourceStability();
                    } else if (playbackLoadingSinceElapsedRealtime < 0L) {
                        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
                    }
                    if (!resourceWarningVisible) hideLoadingState();
                } else if (playbackState == Player.STATE_BUFFERING) {
                    if (playbackLoadingSinceElapsedRealtime < 0L) {
                        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
                    }
                    if (loadingPanel != null
                            && loadingPanel.getVisibility() == View.VISIBLE
                            && !resourceWarningVisible) {
                        showLoadingState(getString(R.string.loading_validating_segment));
                    }
                } else if (playbackState == Player.STATE_IDLE
                        && playbackChannel != null) {
                    if (playbackLoadingSinceElapsedRealtime < 0L) {
                        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
                    }
                }
            }

            @Override public void onPositionDiscontinuity(
                    Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition,
                    int reason
            ) {
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
                applySavedAudioPreference(tracks);
            }

            @Override public void onCues(CueGroup cueGroup) {
                handleSubtitleCues(cueGroup);
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                        && player != null) {
                    // Quedó atrás de la ventana en vivo: se vuelve al borde, no se reconecta.
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                if (revertQualityUpgradeIfRecent()) return;
                markPublishedHighflyLinkFailed();
                settlePlaybackEpisode(false);
                startupMetrics.failed(startupMetrics.currentId());
                playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
                lastPlaybackDiagnosticCode = PlaybackDiagnosticCode.forPlaybackError(
                        error,
                        error.errorCode,
                        ResolvedSourceRefreshPolicy.isManifest(failedRequestUri(error))
                );
                Log.i(PLAYBACK_HEALTH_TAG, "diagnostic code=" + lastPlaybackDiagnosticCode
                        + " media3=" + error.errorCode);
                setStatus("ERROR", R.color.red);
                codecInfo.setText(shortMessage(error));
                overlayAwaitingPlayback = true;
                showOverlay(true);
                if (!requestPlaybackSourceRecovery(error)) {
                    // Automatic reconnection for every channel error. Renewing
                    // a resolver output is attempted first; then up to
                    // PlaybackRecoveryBudget.MAX_ATTEMPTS delayed restarts
                    // (the later ones with a fresh source) before the channel
                    // is reported as failed.
                    requestFullPlaybackRecovery(isDecoderFailure(error)
                            ? "error de decodificador"
                            : "error de reproducción");
                }
            }
        });
        schedulePlaybackWatchdog();
    }
    void playChannel(int requestedIndex) {
        playChannel(requestedIndex, false);
    }

    /**
     * Zapeo (0.5.85). Una pulsación aislada abre el canal al instante. Al mantener CH+/− o pulsar
     * seguido, solo se muestran número, nombre, logo y guía de cada canal; el video abre
     * {@link #ZAP_SETTLE_MS} después de la última pulsación. Antes, cada canal intermedio
     * detenía el reproductor y lanzaba una resolución de red.
     */
    void zapBy(int delta, boolean repeat) {
        if (channels.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        boolean rapid = repeat || zapPreviewPending || now - lastZapKeyAtMs < ZAP_SETTLE_MS;
        lastZapKeyAtMs = now;
        if (!rapid) {
            playChannel(channelIndex + delta);
            return;
        }
        previewChannel(channelIndex + delta);
        mainHandler.removeCallbacks(commitZap);
        mainHandler.postDelayed(commitZap, ZAP_SETTLE_MS);
    }

    /**
     * Primer cuadro del canal. Puede llegar con el estado READY o después, desde el watchdog;
     * en ambos casos se programa la precarga de los vecinos una sola vez (0.5.86).
     */
    void markPlaybackStarted() {
        hidePlaybackErrorScreen();
        if (!playbackHasStarted) {
            scheduleNeighborPrefetch();
            maybeScheduleQualityUpgradeCheck();
        }
        playbackHasStarted = true;
    }

    /**
     * Arranque (0.5.87): resuelve el último canal visto mientras se arma la lista, justo después
     * de cargar del disco las versiones TvVoo y los enlaces Highfly (antes de eso solo veía la
     * versión principal e ignoraba la elegida en el selector). Cuando el canal abre, el coordinador entrega esa misma resolución (en curso o
     * en caché) en vez de empezar de cero. Un canal directo solo abre su conexión.
     */
    void warmLastChannel() {
        Channel last = playbackPreferences == null ? null : playbackPreferences.lastChannelSnapshot();
        if (last == null || streamResolverRegistry == null) return;
        StreamResolver resolver = streamResolverRegistry.find(last);
        if (resolver == null) {
            prefetchExecutor.submit(() -> warmConnection(last));
            return;
        }
        if (!resolver.cacheResolvedSource() || resolver.cacheTtlMillis() <= 0L) return;
        playbackExecutor.submit(() -> {
            ResolutionContext context = new ResolutionContext(15_000L);
            try (ResolutionContext.Scope ignored = context.activate()) {
                resolverCoordinator.resolve(last, resolver, false, ResolutionProgressListener.NONE);
            } catch (Exception ignored) {
                // Si falla, el canal se resuelve como siempre al abrirse.
            }
        });
    }

    /**
     * Abre la conexión al servidor de un canal directo (DNS, TCP y TLS) con el mismo cliente
     * HTTP del reproductor: queda en su pool y la apertura del canal se salta ese saludo.
     */
    static void warmConnection(Channel channel) {
        URI uri = channel == null ? null : channel.getStreamUri();
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) return;
        okhttp3.OkHttpClient client = SharedHttpClient.get().newBuilder()
                .callTimeout(6_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(uri.toString())
                .header("User-Agent", PLAYER_USER_AGENT)
                .header("Range", "bytes=0-1023")
                .build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            okhttp3.ResponseBody body = response.body();
            if (body != null) body.source().request(1024L);
        } catch (Exception ignored) {
            // Solo precalienta: un fallo no afecta la apertura normal.
        }
    }

    void scheduleNeighborPrefetch() {
        mainHandler.removeCallbacks(prefetchNeighbors);
        mainHandler.postDelayed(prefetchNeighbors, NEIGHBOR_PREFETCH_DELAY_MS);
    }

    /**
     * Precarga (0.5.85): con el canal estable, resuelve en segundo plano el anterior y el
     * siguiente. Su fuente queda en la caché del coordinador, así CH+/− abre sin esperar al
     * resolutor. Los canales directos solo adelantan la consulta DNS de su servidor.
     */
    void prefetchNeighborChannels() {
        if (channels.size() < 2 || player == null || !playbackHasStarted || settingsOpen
                || exiting || resourcesReleased || streamResolverRegistry == null) return;
        for (int delta : new int[] {1, -1}) {
            int index = ((channelIndex + delta) % channels.size() + channels.size()) % channels.size();
            Channel neighbor = channels.get(index);
            if (neighbor == null || neighbor == playbackChannel) continue;
            StreamResolver resolver = streamResolverRegistry.find(neighbor);
            if (resolver != null) {
                if (!resolver.cacheResolvedSource() || resolver.cacheTtlMillis() <= 0L) continue;
                prefetchExecutor.submit(() -> {
                    ResolutionContext context = new ResolutionContext(12_000L);
                    try (ResolutionContext.Scope ignored = context.activate()) {
                        resolverCoordinator.resolve(neighbor, resolver, false,
                                ResolutionProgressListener.NONE);
                    } catch (Exception ignored) {
                        // Una precarga fallida no afecta: al abrir el canal se resuelve normal.
                    }
                });
            } else {
                prefetchExecutor.submit(() -> warmConnection(neighbor));
            }
        }
        // El pool cierra conexiones inactivas a los 5 min: se renuevan mientras se mira el canal.
        mainHandler.postDelayed(prefetchNeighbors, NEIGHBOR_REWARM_MS);
    }

    /** Muestra el canal en el OSD sin abrirlo; el que se está viendo sigue sonando. */
    void previewChannel(int requestedIndex) {
        zapPreviewPending = true;
        channelIndex = (requestedIndex % channels.size() + channels.size()) % channels.size();
        Channel channel = channels.get(channelIndex);
        bindOsdChannel(publishedPlaybackCatalog.numberFor(channel, channelIndex + 1), channel);
        updateProgrammeInfo();
        loadChannelLogo(channel, false);
        showOverlayForChannelStart();
    }

    void playChannel(int requestedIndex, boolean revalidateLogo) {
        if (channels.isEmpty()) return;
        hidePlaybackErrorScreen();
        mainHandler.removeCallbacks(commitZap);
        mainHandler.removeCallbacks(prefetchNeighbors);
        zapPreviewPending = false;
        closePlaybackSourceSelector();
        cancelQualityUpgrade();
        qualityRevertSource = null;
        tvvooNoSignalToken++;
        channelIndex = (requestedIndex % channels.size() + channels.size()) % channels.size();
        if (player == null) {
            // Sin foco el reproductor está liberado (0.6.40): el canal se abre al volver.
            restartPlaybackAfterFocusLoss = true;
            return;
        }
        Channel channel = channels.get(channelIndex);
        boolean resumingRecovery = PlaybackPreferences.channelIdentity(channel)
                .equals(pendingTvVooBackupIdentity);
        if (resumingRecovery) {
            tvvooBackupActive = pendingDirectBackupIndex >= 0 && TvVooBackup.has(channel);
            directBackupIndex = Math.max(0, pendingDirectBackupIndex);
        } else {
            tvvooBackupActive = false;
            directBackupIndex = 0;
            backupReturnedToPrincipal = false;
            rememberedBackupStart = applyRememberedSourceChoice(channel);
        }
        pendingTvVooBackupIdentity = null;
        playbackGeneration++;
        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
        playbackHasStarted = false;
        playbackRecoveryCooldownUntilElapsedRealtime = 0L;
        playbackAutoRecoveryInFlight = false;
        playbackRecoveryBudget.reset();
        playbackRecoveryFailed = false;
        playbackSourceRecoveryAttempts = 0;
        playbackSourceRecoveryInFlight = false;
        cancelPlaybackSourceStability();
        lastPlaybackDiagnosticCode = 0;
        resetResourceWarningState();
        resetPlaybackBitrateMeter();
        cancelPlaybackResolution();
        playbackChannel = channel;
        discardCurrentPlaybackSource();
        playbackRecoveryEpisode.reset();
        beginStartupMeasurement(channel, PlaybackStartupMetrics.Reason.CHANNEL);
        qualityPreferenceAppliedFor = null;
        subtitlePreferenceAppliedFor = null;
        subtitleTextObservedFor = null;

        if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build());
            player.stop();
            player.clearMediaItems();
        }
        // 0.6.23: solo se recuerda Lista 1; con Lista 2 se conserva el último de Lista 1.
        if (listOneIdentities.contains(PlaybackPreferences.channelIdentity(channel))) {
            playbackPreferences.rememberChannel(channel, channelIndex);
        }

        int displayNumber = publishedPlaybackCatalog.numberFor(channel, channelIndex + 1);
        bindOsdChannel(displayNumber, channel);
        updateProgrammeInfo();
        videoInfo.setText("— · —");
        codecInfo.setText("— · — · —");
        setStatus("CARGANDO", R.color.amber);
        loadChannelLogo(channel, revalidateLogo);
        showLoadingState(getString(R.string.loading_preparing_channel));
        resolveAndPlay(channel, playbackGeneration);
        showOverlayForChannelStart();
    }

    void prepareAndPlay() {
        if (player == null) return;
        player.prepare();
        player.play();
    }

    void schedulePlaybackWatchdog() {
        if (playbackWatchdogScheduled || exiting || resourcesReleased || isFinishing()) return;
        playbackWatchdogScheduled = true;
        mainHandler.postDelayed(playbackWatchdog, PLAYBACK_WATCHDOG_INTERVAL_MS);
    }

    void cancelPlaybackWatchdog() {
        mainHandler.removeCallbacks(playbackWatchdog);
        playbackWatchdogScheduled = false;
    }

    boolean hasRenderedVideoFrame() {
        return playbackBitrateMeter != null
                && playbackBitrateMeter.snapshot().hasRenderedVideoFrame;
    }

    void maybeSchedulePlaybackSourceStability() {
        if (!playbackHasStarted || currentPlaybackSource == null
                || !"tvvoo".equalsIgnoreCase(currentPlaybackSource.getResolverId())) {
            return;
        }
        if (player == null || player.getPlaybackState() != Player.STATE_READY
                || !player.isPlaying() || !hasRenderedVideoFrame()) return;
        String variant = currentPlaybackSource.getVariantId();
        if (AppStrings.isBlank(variant)) return;
        String sourceKey = PlaybackPreferences.channelIdentity(playbackChannel)
                + "|" + variant;
        if (sourceKey.equals(playbackSourceStabilityScheduledFor)) return;
        playbackSourceStabilityScheduledFor = sourceKey;
        long token = ++playbackSourceStabilityToken;
        long generation = playbackGeneration;
        mainHandler.postDelayed(() -> {
            if (token != playbackSourceStabilityToken
                    || generation != playbackGeneration
                    || playbackChannel == null
                    || currentPlaybackSource == null
                    || !sourceKey.equals(
                    PlaybackPreferences.channelIdentity(playbackChannel)
                            + "|" + currentPlaybackSource.getVariantId())
                    || player == null) return;
            if (player.getPlaybackState() != Player.STATE_READY
                    || !player.isPlaying()
                    || !hasRenderedVideoFrame()) {
                // Buffering time does not count toward source stability. The
                // next READY/playing callback starts a fresh quiet interval.
                playbackSourceStabilityScheduledFor = "";
                return;
            }
            TvVooSourceHistory.recordSuccess(
                    currentPlaybackSource.getStableSourceId(),
                    currentPlaybackSource.getVariantId()
            );
            playbackSourceRecoveryAttempts = 0;
        }, PlaybackRecoveryEpisode.STABLE_PLAYBACK_MS);
    }

    void cancelPlaybackSourceStability() {
        playbackSourceStabilityToken++;
        playbackSourceStabilityScheduledFor = "";
    }

    /**
     * Checks the playback boundary rather than network throughput. A stream
     * can keep downloading bytes while its decoder is no longer receiving
     * frames, so the watchdog also uses the last renderer timestamp.
     */
    void checkPlaybackHealth() {
        if (player == null || playbackChannel == null || settingsOpen || loadFailed
                || playbackRecoveryFailed
                || playbackAutoRecoveryInFlight) return;

        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs < playbackRecoveryCooldownUntilElapsedRealtime) return;
        int state = player.getPlaybackState();
        boolean renderedFrame = hasRenderedVideoFrame();
        if (renderedFrame) {
            markPlaybackStarted();
            maybeSchedulePlaybackSourceStability();
        }
        boolean waitingWithoutFrames = state == Player.STATE_IDLE
                || state == Player.STATE_BUFFERING
                || (state == Player.STATE_READY
                && player.isPlaying()
                && player.getVideoFormat() != null
                && !renderedFrame);
        if (playbackResolutionTask != null) {
            // The resolver has its own bounded HTTP timeout. Do not start a
            // second recovery while a fresh source is still being
            // requested.
            if (playbackLoadingSinceElapsedRealtime < 0L) {
                playbackLoadingSinceElapsedRealtime = nowMs;
            }
            return;
        }
        updateResourceWarnings(state);
        if (waitingWithoutFrames) {
            if (playbackLoadingSinceElapsedRealtime < 0L) {
                playbackLoadingSinceElapsedRealtime = nowMs;
            }
            // Startup buffering belongs to Media3. Once this channel has
            // rendered a frame, the same state means a post-start stall and
            // must not wait forever for Media3 to emit a fatal error.
            PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                    ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
            if (playbackHasStarted
                    && PlaybackStallPolicy.isExpired(
                    playbackLoadingSinceElapsedRealtime,
                    nowMs,
                    PLAYBACK_FREEZE_TIMEOUT_MS
            )) {
                if (PlaybackStallPolicy.keepWaitingWhileReceiving(
                        playbackLoadingSinceElapsedRealtime,
                        nowMs,
                        mediaTransferProgress.lastMediaBytesElapsedMs(),
                        PLAYBACK_RECEIVING_WINDOW_MS,
                        PLAYBACK_MAX_WAIT_WHILE_RECEIVING_MS)) {
                    // El servidor sigue entregando el segmento: reiniciar lo dejaría sin búfer.
                    return;
                }
                requestFullPlaybackRecovery(classifyPlaybackStall(measurements));
            }
            return;
        } else if (state == Player.STATE_READY && renderedFrame) {
            playbackLoadingSinceElapsedRealtime = -1L;
        }

        if (!player.isPlaying() || state != Player.STATE_READY) return;

        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
        long lastFrameNs = measurements.lastRenderedVideoFrameRealtimeNs;
        long nowNs = System.nanoTime();
        if (measurements.hasRenderedVideoFrame
                && lastFrameNs != androidx.media3.common.C.TIME_UNSET
                && PlaybackStallPolicy.isExpired(0L, Math.max(0L, (nowNs - lastFrameNs) / 1_000_000L),
                PLAYBACK_FREEZE_TIMEOUT_MS)) {
            requestFullPlaybackRecovery(classifyPlaybackStall(measurements));
        }
    }

    String classifyPlaybackStall(PlaybackDiagnosticsWorker.Snapshot measurements) {
        long nowNs = System.nanoTime();
        boolean mediaStopped = measurements.lastMediaLoadRealtimeNs <= 0L
                || nowNs - measurements.lastMediaLoadRealtimeNs
                >= PLAYBACK_DIAGNOSTIC_STALL_TIMEOUT_NS;
        boolean audioUnderrun = measurements.lastAudioUnderrunRealtimeNs > 0L
                && nowNs - measurements.lastAudioUnderrunRealtimeNs
                < PLAYBACK_DIAGNOSTIC_STALL_TIMEOUT_NS;
        long bufferedMs = player == null ? 0L : Math.max(0L, player.getTotalBufferedDuration());

        return PlaybackStallPolicy.classify(player != null
                && player.getPlaybackState() == Player.STATE_BUFFERING,
                mediaStopped, audioUnderrun, bufferedMs);
    }

    /**
     * Renews one expired/damaged resolver source while retaining ExoPlayer.
     * The attempt is deliberately bounded; a second source failure falls
     * through to the existing decoder-level full recovery path.
     */
    boolean requestPlaybackSourceRecovery(PlaybackException error) {
        if (isExpiredTvVooCleanSource()) {
            // Los enlaces Clean de TvVoo duran ~20 min: vencer es normal, no una falla.
            playbackSourceRecoveryAttempts = 0;
        }
        if (playbackSourceRecoveryInFlight
                || playbackSourceRecoveryAttempts >= 1
                || playbackChannel == null
                || currentPlaybackSource == null
                || !currentPlaybackSource.isDynamicallyResolved()) return false;
        StreamResolver resolver = streamResolverRegistry == null
                ? null
                : streamResolverRegistry.find(playbackChannel);
        if (resolver == null) return false;
        int responseCode = httpResponseCode(error);
        URI failedUri = failedRequestUri(error);
        if (!ResolvedSourceRefreshPolicy.shouldRefresh(
                responseCode,
                failedUri,
                error == null ? 0 : error.errorCode
        )) return false;

        playbackSourceRecoveryAttempts++;
        playbackSourceRecoveryInFlight = true;
        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
        playbackHasStarted = false;
        playbackAutoRecoveryInFlight = false;
        setStatus("RENOVANDO", R.color.amber);
        showLoadingState(getString(R.string.loading_reopening_source));
        cancelPlaybackSourceStability();
        resolverCoordinator.invalidate(playbackChannel, resolver);
        if (currentPlaybackSource != null
                && "tvvoo".equalsIgnoreCase(currentPlaybackSource.getResolverId())
                && !AppStrings.isBlank(currentPlaybackSource.getVariantId())) {
            TvVooSourceHistory.recordPlaybackFailure(
                    currentPlaybackSource.getStableSourceId(),
                    currentPlaybackSource.getVariantId()
            );
        }
        discardCurrentPlaybackSource();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        resolveAndPlay(playbackChannel, playbackGeneration, true);
        return true;
    }

    static final long TVVOO_CLEAN_LIFETIME_MS = 15L * 60_000L;

    boolean isExpiredTvVooCleanSource() {
        ResolvedPlaybackSource source = currentPlaybackSource;
        if (source == null || !"tvvoo".equalsIgnoreCase(source.getResolverId())) return false;
        URI uri = source.getPlaybackUri();
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
        boolean noFreeze = host.endsWith("hayd.uk");
        return !noFreeze && SystemClock.elapsedRealtime() - currentSourceStartedElapsedRealtime
                >= TVVOO_CLEAN_LIFETIME_MS;
    }

    static boolean isDecoderFailure(PlaybackException error) {
        if (error == null) return false;
        int code = error.errorCode;
        return code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED;
    }

    /**
     * Schedules a bounded, delayed restart of the current channel. Up to
     * {@link PlaybackRecoveryBudget#MAX_ATTEMPTS} attempts per playback episode;
     * from the second one the resolver output is discarded so a fresh source is
     * requested. The budget is restored after stable playback, a channel change
     * or an explicit retry from the remote.
     */
    void requestFullPlaybackRecovery(String reason) {
        requestFullPlaybackRecovery(reason, false, false);
    }

    void requestFullPlaybackRecovery(
            String reason,
            boolean keepDiagnosticCode,
            boolean immediate
    ) {
        if (playbackAutoRecoveryInFlight || playbackChannel == null) return;
        if (!keepDiagnosticCode && reason != null && !"error de reproducción".equals(reason)) {
            lastPlaybackDiagnosticCode = PlaybackDiagnosticCode.forWatchdog(reason);
        }
        if (!isAutoReconnectEnabled()) {
            // Video y audio › Reconexión automática apagada: se muestra el error y OK reintenta.
            showPlaybackFailure();
            return;
        }
        if (!isNetworkAvailable()) {
            waitForNetworkThenRecover();
            return;
        }
        if (switchToTvVooBackup(reason)) return;
        if (!playbackRecoveryBudget.hasAttemptLeft()) {
            if (lastPlaybackDiagnosticCode <= 0) {
                lastPlaybackDiagnosticCode = PlaybackDiagnosticCode.recoveryExhausted();
            }
            showPlaybackFailure();
            return;
        }
        int attempt = playbackRecoveryBudget.consume();
        // TvVoo: la URL que falló rara vez vuelve sola y pedir otra cuesta menos de
        // un segundo, así que desde el primer reintento se resuelve de nuevo.
        boolean renewSource = PlaybackRecoveryBudget.renewsSource(attempt)
                || (currentPlaybackSource != null
                && "tvvoo".equalsIgnoreCase(currentPlaybackSource.getResolverId()));
        long delayMs = immediate ? 0L : PlaybackRecoveryBudget.delayMsFor(attempt);
        playbackAutoRecoveryInFlight = true;
        playbackRecoveryFailed = false;
        Log.i(PLAYBACK_HEALTH_TAG, "recovery reason=" + reason + " attempt=" + attempt
                + " delayMs=" + delayMs + " renewSource=" + renewSource);
        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
        setStatus("RECONECTANDO", R.color.amber);
        codecInfo.setText("Reintento " + attempt + " de " + PlaybackRecoveryBudget.MAX_ATTEMPTS);
        showLoadingState(getString(R.string.loading_reopening_source));

        long token = ++playbackRecoveryToken;
        long generation = playbackGeneration;
        Runnable restart = () -> {
            if (token != playbackRecoveryToken
                    || generation != playbackGeneration
                    || exiting || isFinishing()) return;
            performFullPlaybackRecovery(renewSource);
        };
        if (delayMs <= 0L) {
            restart.run();
        } else {
            mainHandler.postDelayed(restart, delayMs);
        }
    }

    void rememberSourceChoice(Channel channel, String value) {
        if (playbackPreferences != null) playbackPreferences.rememberSourceChoice(channel, value);
        rememberedBackupStart = !PlaybackPreferences.SOURCE_PRINCIPAL.equals(value);
        backupReturnedToPrincipal = false;
    }

    /**
     * Abre el canal en la señal elegida la última vez en el selector (0.5.75). Devuelve true si
     * empieza en un respaldo y no en la señal principal.
     */
    boolean applyRememberedSourceChoice(Channel channel) {
        if (playbackPreferences == null || channel == null || !TvVooBackup.has(channel)) return false;
        String choice = playbackPreferences.getSourceChoice(channel);
        if (PlaybackPreferences.SOURCE_TVVOO_BACKUP.equals(choice) && TvVooBackup.hasTvVoo(channel)) {
            tvvooBackupActive = true;
            return true;
        }
        if (choice.startsWith(PlaybackPreferences.SOURCE_DIRECT_BACKUP_PREFIX)
                && !TvVooBackup.hasTvVoo(channel)) {
            List<URI> backups = TvVooBackup.directBackupsOf(channel);
            for (int index = 0; index < backups.size(); index++) {
                if (choice.equals(PlaybackPreferences.directBackupChoice(backups.get(index)))) {
                    tvvooBackupActive = true;
                    directBackupIndex = index;
                    return true;
                }
            }
        }
        return false;
    }

    /** Canal con el que se resuelve la reproducción: el de respaldo si está activo. */
    Channel resolutionChannelFor(Channel channel) {
        if (!tvvooBackupActive || channel == null) return channel;
        Channel backup = TvVooBackup.hasTvVoo(channel)
                ? TvVooBackup.resolutionChannel(channel)
                : TvVooBackup.directResolutionChannel(channel, directBackupIndex);
        return backup == null ? channel : backup;
    }

    /**
     * Un canal directo con respaldo que falla pasa al respaldo antes de gastar reintentos en
     * el directo; con varios respaldos directos, cada falla pasa al siguiente. Devuelve true
     * si tomó el control de la recuperación.
     */
    boolean switchToTvVooBackup(String reason) {
        Channel channel = playbackChannel;
        if (channel == null || !TvVooBackup.has(channel)) return false;
        // «Cambio automático» apagado en el selector (0.5.95): se reintenta la señal elegida.
        if (playbackPreferences != null && !playbackPreferences.isAutoBackupEnabled()) return false;
        if (tvvooBackupActive) {
            boolean moreDirect = !TvVooBackup.hasTvVoo(channel)
                    && directBackupIndex + 1 < TvVooBackup.directBackupsOf(channel).size();
            if (moreDirect) {
                directBackupIndex++;
            } else if (rememberedBackupStart && !backupReturnedToPrincipal) {
                // La señal recordada del selector y las siguientes fallaron: una vez, la principal.
                backupReturnedToPrincipal = true;
                return switchPlaybackSource(channel, reason, -1);
            } else {
                return false;
            }
        } else {
            if (backupReturnedToPrincipal) return false;
            directBackupIndex = 0;
        }
        return switchPlaybackSource(channel, reason, directBackupIndex);
    }

    /** Pasa a otra señal del canal (índice de respaldo directo, o -1 = principal). */
    boolean switchPlaybackSource(Channel channel, String reason, int backupIndex) {
        Log.i(PLAYBACK_HEALTH_TAG, "backup reason=" + reason + " index=" + backupIndex);
        pendingTvVooBackupIdentity = PlaybackPreferences.channelIdentity(channel);
        pendingDirectBackupIndex = backupIndex;
        tvvooBackupActive = backupIndex >= 0;
        directBackupIndex = Math.max(0, backupIndex);
        playbackAutoRecoveryInFlight = true;
        playbackRecoveryFailed = false;
        setStatus("RESPALDO", R.color.amber);
        showLoadingState(getString(R.string.loading_backup_source));
        long generation = playbackGeneration;
        mainHandler.post(() -> {
            if (generation != playbackGeneration || exiting || isFinishing()) return;
            performFullPlaybackRecovery(false);
        });
        return true;
    }

    /** Antepone el directo («Principal») a las versiones TvVoo de su respaldo. */
    List<ResolvedPlaybackCandidate> withDirectPrincipal(
            Channel channel,
            Channel resolutionChannel,
            List<ResolvedPlaybackCandidate> versions
    ) {
        if (channel == resolutionChannel || versions == null) return versions;
        List<ResolvedPlaybackCandidate> result = new ArrayList<>(versions.size() + 1);
        result.add(ResolvedPlaybackCandidate.version(
                getString(R.string.source_selector_direct_label),
                getString(R.string.source_selector_direct_detail),
                ResolvedPlaybackSource.direct(channel, PLAYER_USER_AGENT),
                "direct",
                "",
                true,
                true,
                0
        ));
        result.addAll(versions);
        return result;
    }

    void performFullPlaybackRecovery(boolean renewSource) {
        Channel channel = playbackChannel;
        if (channel == null) {
            showPlaybackFailure();
            return;
        }
        playbackRecoveryCooldownUntilElapsedRealtime =
                SystemClock.elapsedRealtime() + PLAYBACK_RECOVERY_COOLDOWN_MS;
        long diagnosticCodeBeforeRecovery = lastPlaybackDiagnosticCode;
        int usedAttempts = playbackRecoveryBudget.used();
        String identity = PlaybackPreferences.channelIdentity(channel);
        int targetIndex = findChannelIndexByIdentity(channels, identity);

        cancelPlaybackResolution();
        Channel resolutionChannel = resolutionChannelFor(channel);
        StreamResolver resolver = streamResolverRegistry == null
                ? null
                : streamResolverRegistry.find(resolutionChannel);
        if (renewSource) markPublishedHighflyLinkFailed();
        if (renewSource && resolver != null) {
            // The previous restart reused the validated source and failed
            // again: it may have expired, so ask the resolver for a new one.
            resolverCoordinator.invalidate(resolutionChannel, resolver);
        }
        if (tvvooBackupActive || backupReturnedToPrincipal || rememberedBackupStart) {
            // La reconexión sigue en la misma señal (también si es la principal tras volver).
            pendingTvVooBackupIdentity = identity;
            pendingDirectBackupIndex = tvvooBackupActive ? directBackupIndex : -1;
        }
        // A first watchdog/decoder recovery recreates ExoPlayer but keeps the
        // validated TvVoo/MediaFlow source in the process-only coordinator
        // cache so the new player can attach to it immediately.
        discardCurrentPlaybackSource();
        playbackChannel = null;
        playbackGeneration++;
        releasePlayerForRecovery();
        if (targetIndex < 0 || exiting || isFinishing()) {
            showPlaybackFailure();
            return;
        }

        createPlayer();
        playChannel(targetIndex, false);
        lastPlaybackDiagnosticCode = diagnosticCodeBeforeRecovery;
        // playChannel resets episode-local state. Keep the attempts already
        // spent until playback has been stable for a full episode.
        playbackRecoveryBudget.restore(usedAttempts);
    }

    void releasePlayerForRecovery() {
        if (playbackBitrateMeter != null) {
            playbackBitrateMeter.close();
            playbackBitrateMeter = null;
        }
        if (playbackBufferManager != null) {
            playbackBufferManager.close();
            playbackBufferManager = null;
        }
        ExoPlayer oldPlayer = player;
        player = null;
        if (playerView != null) playerView.setPlayer(null);
        if (oldPlayer != null) {
            try {
                oldPlayer.stop();
                oldPlayer.clearMediaItems();
            } finally {
                oldPlayer.release();
            }
        }
    }

    void cancelPlaybackResolution() {
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

    void resolveAndPlay(Channel channel, long expectedGeneration) {
        resolveAndPlay(channel, expectedGeneration, false);
    }

    void resolveAndPlay(
            Channel channel,
            long expectedGeneration,
            boolean forceRefresh
    ) {
        if (player == null || !isCurrentPlayback(channel, expectedGeneration)) return;
        Channel resolutionChannel = resolutionChannelFor(channel);
        StreamResolver resolver = streamResolverRegistry.find(resolutionChannel);
        if (resolver == null) {
            if (DynamicSourceReference.isAppOnly(resolutionChannel.getStreamUri())) {
                // An unavailable/disabled resolver cannot turn its locator into a media URL.
                showPlaybackFailure();
                return;
            }
            startupMetrics.dequeued(startupMetrics.currentId());
            startupMetrics.resolved(startupMetrics.currentId());
            showLoadingState(getString(R.string.loading_direct_source));
            startResolvedPlayback(
                    channel,
                    ResolvedPlaybackSource.direct(resolutionChannel, PLAYER_USER_AGENT),
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
        // Fuente recordada del selector (Highfly…; 0.5.76). Solo al abrir el canal:
        // las reconexiones usan la resolución normal para no insistir en una fuente caída.
        String rememberedLabel = !forceRefresh && playbackRecoveryBudget.used() == 0
                && playbackPreferences != null && !"tvvoo".equalsIgnoreCase(resolver.getId())
                ? playbackPreferences.getResolverSourceChoice(channel, resolver.getId()) : "";
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
                ResolvedPlaybackSource remembered = rememberedSource(
                        resolutionChannel, resolver, rememberedLabel, progressListener);
                final ResolvedPlaybackSource source = remembered != null
                        ? remembered
                        : resolverCoordinator.resolve(
                                resolutionChannel,
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
                    handleResolutionFailure(channel, resolver, expectedGeneration, error);
                });
            }
        });
    }

    /** La fuente elegida la última vez en el selector, si el resolutor todavía la ofrece. */
    static ResolvedPlaybackSource rememberedSource(
            Channel resolutionChannel,
            StreamResolver resolver,
            String label,
            ResolutionProgressListener progress
    ) {
        if (AppStrings.isBlank(label)) return null;
        try {
            for (ResolvedPlaybackCandidate candidate
                    : resolver.resolvePlaybackCandidates(resolutionChannel, progress)) {
                ResolvedPlaybackSource source = candidate.getSource();
                if (label.equals(candidate.getLabel()) && candidate.isAvailable() && source != null
                        && !source.isExpired(System.currentTimeMillis())) {
                    return source;
                }
            }
        } catch (Exception ignored) {
            // Sin la fuente recordada se usa la resolución normal.
        }
        return null;
    }

    void handleResolutionFailure(
            Channel channel,
            StreamResolver resolver,
            long expectedGeneration,
            Throwable error
    ) {
        playbackSourceRecoveryInFlight = false;
        lastPlaybackDiagnosticCode = PlaybackDiagnosticCode.forResolverFailure(error);
        Log.i(PLAYBACK_HEALTH_TAG, "diagnostic code=" + lastPlaybackDiagnosticCode);
        if (isPremiumRejection(error)) {
            // Reintentar no sirve: Highfly rechazó el token. Se avisa con la escena.
            playbackAutoRecoveryInFlight = false;
            showPlaybackFailure();
            showPremiumRejected(channel);
            return;
        }
        // TvVoo/Highfly can fail intermittently: retry with the bounded budget
        // (a fresh source from the second attempt) before reporting ERROR.
        playbackAutoRecoveryInFlight = false;
        if (playbackChannel == null) {
            showPlaybackFailure();
            return;
        }
        if (resolver != null && "tvvoo".equalsIgnoreCase(resolver.getId())
                && isNetworkAvailable() && playbackRecoveryBudget.used() > 0) {
            // La carrera ya probó todas las versiones dos veces: el canal está caído en el
            // origen. Sin reintentos ruidosos; se prueba en silencio cada minuto.
            showTvVooNoSignal();
            return;
        }
        requestFullPlaybackRecovery("fuente no disponible", true, false);
    }

    // ---------------------------------------------------------------------------------
    // Mejor calidad disponible (TvVoo, 2026-10-03)
    // ---------------------------------------------------------------------------------

    /**
     * Aviso de calidad superior (robusto desde 0.5.87). Antes se programaba una sola vez y solo
     * si el primer cuadro llegaba junto con READY; si a los 15 s el video cargaba o aún no tenía
     * altura, se abandonaba. Ahora: búsquedas a los 3 s, 2 min y 10 min de reproducción; si el
     * momento no sirve (cargando, menú abierto, altura desconocida) se reintenta cada 5 s; se
     * deja de ofrecer en ese canal si el usuario lo rechaza o deja pasar el aviso.
     */
    static final long[] QUALITY_CHECK_DELAYS_MS = {3_000L, 120_000L, 600_000L};
    static final long QUALITY_CHECK_RETRY_MS = 5_000L;
    static final int QUALITY_CHECK_MAX_DEFERRALS = 12;
    static final long QUALITY_UPGRADE_VISIBLE_MS = 12_000L;
    static final long QUALITY_REVERT_WINDOW_MS = 20_000L;
    static final double QUALITY_UPGRADE_MIN_SPEED = 1.5d;
    static final String QUALITY_TAG = "VibeM3U-Quality";

    final Runnable qualityUpgradeCheck = this::startQualityUpgradeCheck;
    final Runnable hideQualityUpgradeRunnable = this::hideQualityUpgrade;
    static final long QUALITY_CHECK_MIN_BUFFER_MS = 8_000L;
    int qualityCheckAttempt;
    int qualityCheckDeferrals;
    boolean qualityUpgradeDeclined;
    /** Tras aceptar (o volver de) un cambio, se busca de nuevo sobre la calidad nueva. */
    boolean qualityCheckRestartPending;
    /** Versiones que fallaron al cambiar en esta visita al canal: no se vuelven a ofrecer. */
    final java.util.Set<String> qualityFailedVariants = new java.util.HashSet<>();

    /** Empieza las búsquedas del canal recién abierto (o de la versión recién aceptada). */
    void maybeScheduleQualityUpgradeCheck() {
        boolean sameVisit = qualityUpgradeCheckedGeneration == playbackGeneration;
        if (sameVisit && !qualityCheckRestartPending) return;
        if (!sameVisit) qualityFailedVariants.clear();
        qualityCheckRestartPending = false;
        qualityUpgradeCheckedGeneration = playbackGeneration;
        qualityCheckAttempt = 0;
        qualityCheckDeferrals = 0;
        qualityUpgradeDeclined = false;
        scheduleNextQualityCheck(playbackGeneration);
    }

    void scheduleNextQualityCheck(long generation) {
        mainHandler.removeCallbacks(qualityUpgradeCheck);
        if (generation != playbackGeneration || qualityUpgradeDeclined
                || qualityCheckAttempt >= QUALITY_CHECK_DELAYS_MS.length) return;
        mainHandler.postDelayed(qualityUpgradeCheck, QUALITY_CHECK_DELAYS_MS[qualityCheckAttempt]);
    }

    void deferQualityCheck(String reason) {
        if (++qualityCheckDeferrals > QUALITY_CHECK_MAX_DEFERRALS) {
            Log.i(QUALITY_TAG, "sin búsqueda: " + reason + " (se agotaron los reintentos)");
            return;
        }
        Log.i(QUALITY_TAG, "búsqueda aplazada: " + reason);
        mainHandler.removeCallbacks(qualityUpgradeCheck);
        mainHandler.postDelayed(qualityUpgradeCheck, QUALITY_CHECK_RETRY_MS);
    }

    void startQualityUpgradeCheck() {
        Channel channel = playbackChannel;
        if (channel == null || exiting || streamResolverRegistry == null
                || qualityUpgradeDeclined || qualityUpgradeCheckedGeneration != playbackGeneration) return;
        if (qualityUpgradeTask != null) {
            deferQualityCheck("otra búsqueda en curso");
            return;
        }
        ResolvedPlaybackSource current = currentPlaybackSource;
        Channel resolutionChannel = resolutionChannelFor(channel);
        StreamResolver resolver = streamResolverRegistry.find(resolutionChannel);
        if (current == null || resolver == null || !"tvvoo".equalsIgnoreCase(resolver.getId())
                || !"tvvoo".equalsIgnoreCase(current.getResolverId())) return;
        Format video = player == null ? null : player.getVideoFormat();
        int currentHeight = video == null ? 0 : Math.max(0, video.height);
        if (player == null || !player.isPlaying()) {
            deferQualityCheck("el video no está reproduciendo");
            return;
        }
        if (currentHeight <= 0) {
            deferQualityCheck("altura del video aún desconocida");
            return;
        }
        // La búsqueda comparte red y proveedor con el video: solo con colchón suficiente.
        if (player.getTotalBufferedDuration() < QUALITY_CHECK_MIN_BUFFER_MS) {
            deferQualityCheck("búfer bajo (" + player.getTotalBufferedDuration() + " ms)");
            return;
        }
        if (isSourceSelectorVisible() || isGuideVisible() || settingsOpen || isQualityUpgradeVisible()) {
            deferQualityCheck("hay un menú abierto");
            return;
        }
        int attempt = ++qualityCheckAttempt;
        long generation = playbackGeneration;
        java.util.Set<String> skip = new java.util.HashSet<>(qualityFailedVariants);
        Log.i(QUALITY_TAG, "búsqueda " + attempt + " · actual " + currentHeight + "p · "
                + TvVooStreamResolver.versionName(current.getVariantId()));
        ResolutionContext context = new ResolutionContext(30_000L);
        qualityUpgradeContext = context;
        qualityUpgradeTask = prefetchExecutor.submit(() -> {
            String outcome = "sin versión de mayor calidad";
            try (ResolutionContext.Scope ignored = context.activate()) {
                long scanStarted = SystemClock.elapsedRealtime();
                List<ResolvedPlaybackCandidate> rows = resolver instanceof TvVooStreamResolver
                        ? ((TvVooStreamResolver) resolver).findBetterThan(resolutionChannel, currentHeight)
                        : resolver.resolvePlaybackCandidates(resolutionChannel, ResolutionProgressListener.NONE);
                long scanMs = SystemClock.elapsedRealtime() - scanStarted;
                ResolvedPlaybackCandidate best = null;
                for (ResolvedPlaybackCandidate row : rows) {
                    if (!row.isAvailable() || row.getSource() == null) continue;
                    if (row.getQualityHeight() <= currentHeight) continue;
                    if (row.getVariantId().equals(current.getVariantId())) continue;
                    if (skip.contains(row.getVariantId())) continue;
                    if (best == null || row.getQualityHeight() > best.getQualityHeight()) best = row;
                }
                outcome = "sin versión de mayor calidad (" + scanMs + " ms)";
                if (best != null) {
                    long measureStarted = SystemClock.elapsedRealtime();
                    // Prueba exigente: dos segmentos completos, bien por sobre tiempo real.
                    HlsStreamValidator.Sustained sustained = new HlsStreamValidator(
                            new TokenHttpClient(4_000, 10_000)).measureSustained(
                            best.getSource().getPlaybackUri(),
                            best.getSource().getRequestHeaders(),
                            context);
                    String timing = " · búsqueda " + scanMs + " ms, prueba "
                            + (SystemClock.elapsedRealtime() - measureStarted) + " ms";
                    if (sustained.speed < QUALITY_UPGRADE_MIN_SPEED) {
                        outcome = best.getQualityHeight() + "p no aguanta tiempo real ("
                                + String.format(Locale.ROOT, "%.1f", sustained.speed) + "x)" + timing;
                    } else if (sustained.info != null && sustained.info.height <= currentHeight) {
                        outcome = "el video real no supera " + currentHeight + "p" + timing;
                    } else {
                        ResolvedPlaybackCandidate offer = best;
                        outcome = "oferta " + offer.getQualityHeight() + "p" + timing;
                        mainHandler.post(() -> {
                            if (!showQualityUpgrade(channel, generation, offer, currentHeight)) {
                                Log.i(QUALITY_TAG, "oferta no mostrada: canal cambiado o menú abierto");
                                scheduleNextQualityCheck(generation);
                            }
                        });
                    }
                }
            } catch (Exception error) {
                outcome = "falló la búsqueda: " + error.getClass().getSimpleName();
            } finally {
                String result = outcome;
                mainHandler.post(() -> {
                    if (qualityUpgradeContext == context) {
                        qualityUpgradeContext = null;
                        qualityUpgradeTask = null;
                    }
                    Log.i(QUALITY_TAG, "búsqueda " + attempt + ": " + result);
                    if (!result.startsWith("oferta")) scheduleNextQualityCheck(generation);
                });
            }
        });
    }

    boolean showQualityUpgrade(
            Channel channel,
            long generation,
            ResolvedPlaybackCandidate offer,
            int currentHeight
    ) {
        if (channel != playbackChannel || generation != playbackGeneration || exiting
                || isSourceSelectorVisible() || isGuideVisible() || settingsOpen
                || (exitDialog != null && exitDialog.isShowing())
                || (premiumSceneDialog != null && premiumSceneDialog.isShowing())) return false;
        qualityUpgradeOffer = offer.getSource();
        String quality = offer.getQuality();
        String shortQuality = quality.contains(" · ") ? quality.substring(0, quality.indexOf(" · ")) : quality;
        qualityUpgradeTitle.setText(getString(R.string.quality_upgrade_title,
                channel.getName(), shortQuality));
        qualityUpgradeMessage.setText(getString(R.string.quality_upgrade_message,
                offer.getLabel(), quality.replace(" · ", " a "), currentHeight));
        qualityUpgradeOverlay.setVisibility(View.VISIBLE);
        qualityUpgradeSwitch.requestFocus();
        if (qualityUpgradeTimerAnimation != null) qualityUpgradeTimerAnimation.cancel();
        qualityUpgradeTimer.setProgress(1000);
        qualityUpgradeTimerAnimation = android.animation.ObjectAnimator.ofInt(
                qualityUpgradeTimer, "progress", 1000, 0);
        qualityUpgradeTimerAnimation.setDuration(QUALITY_UPGRADE_VISIBLE_MS);
        qualityUpgradeTimerAnimation.setInterpolator(new android.view.animation.LinearInterpolator());
        qualityUpgradeTimerAnimation.start();
        mainHandler.removeCallbacks(hideQualityUpgradeRunnable);
        mainHandler.postDelayed(hideQualityUpgradeRunnable, QUALITY_UPGRADE_VISIBLE_MS);
        Log.i(QUALITY_TAG, "aviso mostrado: " + offer.getLabel() + " · " + offer.getQuality());
        return true;
    }

    boolean isQualityUpgradeVisible() {
        return qualityUpgradeOverlay != null && qualityUpgradeOverlay.getVisibility() == View.VISIBLE;
    }

    boolean handleQualityUpgradeKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        // Solo se toman ◀ ▶ y OK. Atrás sigue su curso normal y handleBackAction cierra el aviso.
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                qualityUpgradeSwitch.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                qualityUpgradeDismiss.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (qualityUpgradeDismiss.isFocused()) hideQualityUpgrade();
                else acceptQualityUpgrade();
                return true;
            default:
                // Las demás teclas siguen su curso; cambiar de canal cancela el aviso.
                return false;
        }
    }

    void hideQualityUpgrade() {
        // Rechazado o dejado pasar: no se vuelve a ofrecer en esta visita al canal.
        if (isQualityUpgradeVisible()) qualityUpgradeDeclined = true;
        mainHandler.removeCallbacks(hideQualityUpgradeRunnable);
        if (qualityUpgradeTimerAnimation != null) qualityUpgradeTimerAnimation.cancel();
        qualityUpgradeOffer = null;
        if (qualityUpgradeOverlay != null) qualityUpgradeOverlay.setVisibility(View.GONE);
    }

    void cancelQualityUpgrade() {
        mainHandler.removeCallbacks(qualityUpgradeCheck);
        if (qualityUpgradeContext != null) qualityUpgradeContext.cancel();
        if (qualityUpgradeTask != null) qualityUpgradeTask.cancel(true);
        qualityUpgradeContext = null;
        qualityUpgradeTask = null;
        hideQualityUpgrade();
    }

    /** Cambia a la fuente mejor; si falla en los 20 s siguientes, vuelve sola a la anterior. */
    void acceptQualityUpgrade() {
        ResolvedPlaybackSource offer = qualityUpgradeOffer;
        Channel channel = playbackChannel;
        hideQualityUpgrade();
        if (offer == null || channel == null || player == null
                || offer.isExpired(System.currentTimeMillis())) return;
        Log.i(QUALITY_TAG, "aviso aceptado");
        // La próxima apertura del canal usa esta versión: queda en la caché y se prueba primero.
        Channel resolutionChannel = resolutionChannelFor(channel);
        StreamResolver resolver = streamResolverRegistry == null ? null
                : streamResolverRegistry.find(resolutionChannel);
        if (resolver != null) resolverCoordinator.remember(resolutionChannel, resolver, offer);
        if (!AppStrings.isBlank(offer.getVariantId())) {
            TvVooSourceHistory.pinAlias(offer.getStableSourceId(), offer.getVariantId());
        }
        qualityRevertSource = currentPlaybackSource;
        qualityRevertUntilElapsedRealtime = SystemClock.elapsedRealtime() + QUALITY_REVERT_WINDOW_MS;
        // Si hay una aún mejor (p. ej. 480p → 720p y existe 1080p), se ofrece después.
        qualityCheckRestartPending = true;
        switchToSource(channel, offer);
    }

    boolean revertQualityUpgradeIfRecent() {
        ResolvedPlaybackSource previous = qualityRevertSource;
        Channel channel = playbackChannel;
        qualityRevertSource = null;
        if (previous == null || channel == null || player == null
                || SystemClock.elapsedRealtime() > qualityRevertUntilElapsedRealtime
                || previous.isExpired(System.currentTimeMillis())) return false;
        ResolvedPlaybackSource failed = currentPlaybackSource;
        if (failed != null && !AppStrings.isBlank(failed.getVariantId())) {
            qualityFailedVariants.add(failed.getVariantId());
        }
        Log.i(QUALITY_TAG, "la versión nueva falló: se vuelve a la anterior");
        // La próxima apertura vuelve a usar la versión que sí funcionaba.
        Channel resolutionChannel = resolutionChannelFor(channel);
        StreamResolver resolver = streamResolverRegistry == null ? null
                : streamResolverRegistry.find(resolutionChannel);
        if (resolver != null) resolverCoordinator.remember(resolutionChannel, resolver, previous);
        if (!AppStrings.isBlank(previous.getVariantId())) {
            TvVooSourceHistory.pinAlias(previous.getStableSourceId(), previous.getVariantId());
        }
        qualityCheckRestartPending = true;
        switchToSource(channel, previous);
        return true;
    }

    void switchToSource(Channel channel, ResolvedPlaybackSource source) {
        if (!isCurrentPlayback(channel, playbackGeneration)) return;
        playbackHasStarted = false;
        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
        playbackAutoRecoveryInFlight = false;
        playbackRecoveryFailed = false;
        resetPlaybackBitrateMeter();
        player.stop();
        player.clearMediaItems();
        discardCurrentPlaybackSource();
        long requestId = ++playbackResolutionRequestId;
        setStatus("CARGANDO", R.color.amber);
        showLoadingState(getString(R.string.source_selector_switching));
        if (DynamicSourceReference.isAppOnly(source.getPlaybackUri())) {
            // The selector row carries a tokenless locator, not a playable URL.
            resolveAndPlay(channel, playbackGeneration, true);
            return;
        }
        startResolvedPlayback(channel, source, playbackGeneration, requestId);
    }

    // ---------------------------------------------------------------------------------
    // Reconexión: canal TvVoo sin señal y espera de red (2026-10-03)
    // ---------------------------------------------------------------------------------

    static final long TVVOO_NO_SIGNAL_RETRY_MS = 60_000L;

    void showTvVooNoSignal() {
        showPlaybackFailure();
        setStatus("SIN SEÑAL", R.color.amber);
        codecInfo.setText(getString(R.string.tvvoo_no_signal));
        long token = ++tvvooNoSignalToken;
        long generation = playbackGeneration;
        mainHandler.postDelayed(() -> {
            if (token != tvvooNoSignalToken || generation != playbackGeneration
                    || exiting || isFinishing() || playbackChannel == null) return;
            playbackRecoveryBudget.reset();
            performFullPlaybackRecovery(true);
        }, TVVOO_NO_SIGNAL_RETRY_MS);
    }

    void waitForNetworkThenRecover() {
        setStatus("SIN INTERNET", R.color.amber);
        showLoadingState(getString(R.string.waiting_network));
        if (waitingForNetwork) return;
        waitingForNetwork = true;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        networkWaitCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                mainHandler.post(() -> {
                    if (!waitingForNetwork || exiting || isFinishing()) return;
                    stopWaitingForNetwork();
                    playbackRecoveryBudget.reset();
                    requestFullPlaybackRecovery("red recuperada", true, true);
                });
            }
        };
        try {
            manager.registerDefaultNetworkCallback(networkWaitCallback);
        } catch (RuntimeException ignored) {
            waitingForNetwork = false;
            networkWaitCallback = null;
        }
    }

    void stopWaitingForNetwork() {
        waitingForNetwork = false;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager != null && networkWaitCallback != null) {
            try {
                manager.unregisterNetworkCallback(networkWaitCallback);
            } catch (RuntimeException ignored) {
                // Ya no estaba registrado.
            }
        }
        networkWaitCallback = null;
    }

    static boolean isPremiumRejection(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HighflyPremiumClient.RejectedException) return true;
        }
        return false;
    }

    /** Token Premium vencido o revocado: vincular de nuevo o ver la señal gratuita. */
    void showPremiumRejected(Channel channel) {
        if (premiumSceneDialog != null && premiumSceneDialog.isShowing()) return;
        String slug = HighflyStreamResolver.premiumSlug(channel);
        premiumSceneDialog = PremiumScenes.showRejected(
                this,
                channel == null ? "" : channel.getName(),
                () -> premiumSceneDialog = PremiumScenes.showPairing(
                        this, this::retryAfterPremiumChange),
                () -> {
                    HighflyPremiumSession.useFreeFor(slug);
                    retryAfterPremiumChange();
                }
        );
    }

    void retryAfterPremiumChange() {
        if (playbackChannel == null) return;
        playbackRecoveryBudget.reset();
        playbackRecoveryFailed = false;
        playbackAutoRecoveryInFlight = false;
        requestFullPlaybackRecovery("Highfly Premium", true, true);
    }

    void startResolvedPlayback(
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
        resetPlaybackBitrateMeter();
        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
        playbackAutoRecoveryInFlight = false;
        playbackRecoveryFailed = false;
        activePlaybackSourceRequestId = source.isDynamicallyResolved()
                ? expectedResolutionRequestId
                : NO_RESOLUTION_REQUEST;
        currentPlaybackSource = source;
        currentSourceStartedElapsedRealtime = SystemClock.elapsedRealtime();
        playbackSourceRecoveryInFlight = false;
        playbackSourceStabilityScheduledFor = "";
        if (playbackManifestCache != null) {
            ManifestHandoffCache handoff = playbackManifestCache;
            mainHandler.postDelayed(handoff::clear, ManifestHandoffCache.DEFAULT_TTL_MILLIS);
        }
        final MediaSource mediaSource;
        try {
            mediaSource = mediaSourceFor(channel, source);
        } catch (java.io.IOException error) {
            // A bad MediaFlow origin/configuration must fail this source
            // cleanly; never hand an unguarded URL to Media3.
            currentPlaybackSource = null;
            activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
            handleResolutionFailure(
                    channel,
                    streamResolverRegistry.find(channel),
                    expectedGeneration,
                    error
            );
            return;
        }
        if (player == null) return;
        player.setMediaSource(mediaSource);
        showLoadingState(getString(R.string.loading_starting_playback));
        prepareAndPlay();
    }

    void discardCurrentPlaybackSource() {
        cancelPlaybackSourceStability();
        currentPlaybackSource = null;
        if (playbackManifestCache != null) playbackManifestCache.clear();
        playbackManifestCache = null;
        activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    }

    void resetPlaybackBitrateMeter() {
        playbackDiagnosticsActive = false;
        if (playbackBitrateMeter != null) playbackBitrateMeter.reset();
    }

    MediaSource mediaSourceFor(Channel channel, ResolvedPlaybackSource source)
            throws java.io.IOException {
        String userAgent = AppStrings.isBlank(source.getUserAgent())
                ? PLAYER_USER_AGENT
                : source.getUserAgent();
        OkHttpClient playbackClient = MediaFlowPlaybackClient.forSource(source);
        OkHttpDataSource.Factory dataSourceFactory =
                new OkHttpDataSource.Factory(playbackClient).setUserAgent(userAgent)
                        .setTransferListener(mediaTransferProgress);
        Map<String, String> headers = PlaybackRequestHeaders.withoutUserAgent(
                source.getRequestHeaders()
        );
        if (!headers.isEmpty()) {
            dataSourceFactory.setDefaultRequestProperties(headers);
        }
        DataSource.Factory playbackDataSourceFactory = dataSourceFactory;
        // Idioma de audio en TS con varias pistas (0.6.12): el filtro solo edita la tabla PMT.
        TsAudioSession tsAudio = new TsAudioSession(TsAudioSession.languageOfPreference(
                playbackPreferences == null ? "" : playbackPreferences.getAudio(channel)));
        currentTsAudioSession = tsAudio;
        // Sincronía del audio guardada para este canal (0.6.29).
        AudioSyncOffset.setMs(playbackPreferences == null ? 0
                : playbackPreferences.getAudioSyncMs(channel));
        playbackDataSourceFactory = new TsAudioFilterDataSource.Factory(
                playbackDataSourceFactory, tsAudio);
        if (playbackManifestCache != null) {
            playbackDataSourceFactory = new ManifestHandoffDataSource.Factory(
                    playbackDataSourceFactory, playbackManifestCache);
        }
        if ("meganoticias".equalsIgnoreCase(source.getResolverId())) {
            playbackDataSourceFactory = new MeganoticiasPlaylistDataSource.Factory(
                    playbackDataSourceFactory
            );
        }
        if ("highfly".equalsIgnoreCase(source.getResolverId())) {
            playbackDataSourceFactory = new HighflyPlaylistDataSource.Factory(
                    playbackDataSourceFactory
            );
        }
        MediaItem.Builder itemBuilder = mediaItemFor(channel, source.getPlaybackUri()).buildUpon();
        if (source.hasMimeType()) itemBuilder.setMimeType(source.getMimeType());
        boolean slowRelay = isSlowRelay(source);
        // Velocidad fija en 1x en todos los canales en vivo (0.6.29, pedido del usuario): al
        // acelerar o frenar para seguir el vivo, el audio pasa por un estirador de tiempo que
        // puede correrlo respecto del video. Si se atrasa, alcanza el vivo saltando.
        MediaItem.LiveConfiguration.Builder live = new MediaItem.LiveConfiguration.Builder()
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f);
        if (slowRelay) {
            // Lista de 60 s con segmentos de 5 s que el servidor entrega a saltos: 30 s detrás
            // del vivo hay segmentos ya publicados para adelantar.
            live.setTargetOffsetMs(SLOW_RELAY_LIVE_OFFSET_MS);
        }
        itemBuilder.setLiveConfiguration(live.build());
        if (playbackBufferManager != null) {
            playbackBufferManager.setMinimumStartBufferMs(slowRelay ? SLOW_RELAY_START_BUFFER_MS : 0L);
        }
        mediaTransferProgress.reset();
        return new DefaultMediaSourceFactory(playbackDataSourceFactory)
                .setLoadErrorHandlingPolicy(new PlaybackLoadErrorPolicy(source.isDynamicallyResolved()))
                .createMediaSource(itemBuilder
                        .setTag(Long.valueOf(startupMetrics.currentId())).build());
    }

    /** Fuentes de servidor lento y a saltos (Nauta): más margen al vivo y al arrancar. */
    static boolean isSlowRelay(ResolvedPlaybackSource source) {
        return source != null && NautaStreamResolver.ID.equalsIgnoreCase(source.getResolverId());
    }

    void beginStartupMeasurement(Channel channel, PlaybackStartupMetrics.Reason reason) {
        StreamResolver resolver = channel == null || streamResolverRegistry == null
                ? null : streamResolverRegistry.find(channel);
        startupMetrics.begin(resolver == null ? "direct" : resolver.getId(), reason);
    }

    void settlePlaybackEpisode(boolean playing) {
        if (!playbackRecoveryEpisode.onPlayingChanged(playing, System.nanoTime())) return;
        playbackRecoveryBudget.reset();
    }

    boolean isCurrentPlayback(Channel channel, long expectedGeneration) {
        return !exiting
                && !isFinishing()
                && expectedGeneration == playbackGeneration
                && playbackChannel == channel;
    }

    void showPlaybackFailure() {
        playbackAutoRecoveryInFlight = false;
        playbackRecoveryFailed = true;
        playbackLoadingSinceElapsedRealtime = -1L;
        if (lastPlaybackDiagnosticCode <= 0) {
            lastPlaybackDiagnosticCode = PlaybackDiagnosticCode.recoveryExhausted();
        }
        startupMetrics.failed(startupMetrics.currentId());
        setStatus("ERROR", R.color.red);
        codecInfo.setText(PlaybackDiagnosticCode.display(lastPlaybackDiagnosticCode));
        hideLoadingState();
        if (!classicUi && playbackErrorOverlay != null) {
            showPlaybackErrorScreen();
            return;
        }
        overlayAwaitingPlayback = true;
        showOverlay(true);
    }

    void startPlaybackFromInput() {
        if (player == null) return;
        if (playbackRecoveryFailed) {
            // OK/Play sobre un ERROR siempre reintenta, con reintentos repuestos.
            playbackRecoveryBudget.reset();
            playbackRecoveryFailed = false;
            playbackAutoRecoveryInFlight = false;
            requestFullPlaybackRecovery("reanudación tras error", true, true);
            return;
        }
        if (playbackResolutionTask != null || playbackAutoRecoveryInFlight) {
            // El canal ya está cargando o reconectando: no reiniciarlo.
            return;
        }
        if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE) {
            requestFullPlaybackRecovery("reanudación tras error", false, true);
            return;
        }
        player.play();
    }

    /**
     * Stops playback when hidden but retains only unexpired in-session tokens.
     * Al perder el foco (0.6.40) el reproductor se libera entero: el decodificador por
     * hardware, sus búferes de video y la memoria de carga quedan libres para otras apps.
     * Al volver se crea uno nuevo.
     */
    void stopPlaybackForFocusLoss() {
        boolean hasPlaybackSession = playbackChannel != null
                || currentPlaybackSource != null
                || (player != null && player.getCurrentMediaItem() != null);
        if (!hasPlaybackSession) {
            releasePlayerForRecovery();
            return;
        }
        restartPlaybackAfterFocusLoss = true;
        cancelPlaybackResolution();
        resolverCoordinator.clearForPlaybackPause();
        playbackGeneration++;
        playbackHasStarted = false;
        playbackLoadingSinceElapsedRealtime = -1L;
        playbackAutoRecoveryInFlight = false;
        playbackRecoveryFailed = false;
        playbackRecoveryBudget.reset();
        discardCurrentPlaybackSource();
        playbackChannel = null;
        releasePlayerForRecovery();
    }

    /** Recrea el reproductor liberado al perder el foco. */
    void ensurePlayerAfterFocusLoss() {
        if (player == null && !resourcesReleased && !isFinishing()) createPlayer();
    }

    /** Reopens the previously selected channel with a fresh source/session. */
    void restartCurrentPlaybackAfterFocusLoss() {
        if (resourcesReleased || settingsOpen || channels.isEmpty()) return;
        int safeIndex = Math.max(0, Math.min(channelIndex, channels.size() - 1));
        if (player == null) createPlayer();
        playChannel(safeIndex, false);
    }

    static MediaItem mediaItemFor(Channel channel, URI playbackUri) {
        Uri uri = Uri.parse(playbackUri.toString());
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(uri)
                .setMediaId(PlaybackPreferences.channelIdentity(channel));
        if (isHlsUri(uri)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        return builder.build();
    }

    static boolean isHlsUri(Uri uri) {
        String path = uri.getPath();
        return path != null && path.toLowerCase(Locale.ROOT).contains(".m3u8");
    }
    void updateStreamStatus(int state) {
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

    void maybeActivatePlaybackDiagnostics() {
        if (playbackDiagnosticsActive || player == null || playbackBitrateMeter == null) {
            return;
        }
        if (player.getPlaybackState() == Player.STATE_READY
                && playbackBitrateMeter.snapshot().hasRenderedVideoFrame) {
            playbackDiagnosticsActive = true;
        }
    }

    /** Called by the diagnostics worker; never touches Player or View objects on that thread. */
    void requestDiagnosticsUpdate() {
        if (!diagnosticsUpdateQueued.compareAndSet(false, true)) return;
        if (!mainHandler.post(applyMeasuredDiagnostics)) diagnosticsUpdateQueued.set(false);
    }

    void updateDiagnosticsVisibility() {
        if (playbackBitrateMeter != null) {
            playbackBitrateMeter.setNotificationsEnabled(!resourcesReleased && !settingsOpen
                    && hasWindowFocus() && channelOverlay != null && channelOverlay.isShown());
        }
    }

    void updateDiagnostics() {
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

    static void setTextIfChanged(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    static String compactBitrate(long bitsPerSecond) {
        return bitsPerSecond > 0 ? formatBitrate(bitsPerSecond) : "—";
    }

    static int declaredBitrate(Format format) {
        if (format == null) return Format.NO_VALUE;
        if (format.averageBitrate > 0) return format.averageBitrate;
        return format.peakBitrate > 0 ? format.peakBitrate : Format.NO_VALUE;
    }

    static boolean isMuxedAudioVideo(Tracks tracks) {
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

    static int declaredStreamBitrate(Format video, Format audio) {
        int videoBitrate = declaredBitrate(video);
        return videoBitrate > 0 ? videoBitrate : declaredBitrate(audio);
    }

    static String formatBitrate(long bitsPerSecond) {
        if (bitsPerSecond >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000d);
        }
        return String.format(Locale.ROOT, "%.0f kbps", bitsPerSecond / 1_000d);
    }

    void applySavedQualityPreference(Tracks tracks) {
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

    /**
     * Pista de audio guardada para el canal (0.6.11). Solo actúa si suena otra: así no hay que
     * recordar si ya se aplicó, y tras una reconexión (pistas nuevas) se vuelve a elegir sola.
     */
    void applySavedAudioPreference(Tracks tracks) {
        if (player == null || channels.isEmpty()
                || channelIndex < 0 || channelIndex >= channels.size()) return;
        Channel channel = channels.get(channelIndex);
        MediaItem mediaItem = player.getCurrentMediaItem();
        if (mediaItem == null
                || !PlaybackPreferences.channelIdentity(channel).equals(mediaItem.mediaId)) return;
        String saved = playbackPreferences.getAudio(channel);
        if (AppStrings.isBlank(saved)) return;
        List<AudioTrackOptions.Option> options = AudioTrackOptions.collect(tracks);
        if (options.size() < 2) return;
        AudioTrackOptions.Option match = AudioTrackOptions.find(options, saved);
        if (match == null || match.selected) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setOverrideForType(new TrackSelectionOverride(
                        tracks.getGroups().get(match.groupIndex).getMediaTrackGroup(),
                        match.trackIndex))
                .build());
    }

    void applySavedSubtitlePreference(Tracks tracks) {
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

    void handleSubtitleCues(CueGroup cueGroup) {
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

    static boolean hasNonBlankTextCue(CueGroup cueGroup) {
        for (Cue cue : cueGroup.cues) {
            if (cue.text != null && !AppStrings.isBlank(cue.text.toString())) {
                return true;
            }
        }
        return false;
    }

    boolean hasObservedSubtitleText(Channel channel) {
        return PlaybackPreferences.channelIdentity(channel).equals(subtitleTextObservedFor);
    }

    static boolean hasSupportedTextTrack(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT
                    && group.isSupported()
                    && group.length > 0) {
                return true;
            }
        }
        return false;
    }

    void useAutomaticQuality(Channel channel) {
        playbackPreferences.useAutomaticQuality(channel);
        qualityPreferenceAppliedFor = PlaybackPreferences.channelIdentity(channel);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build());
    }

    void applyFixedQuality(
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

    static List<VideoTrackOption> collectVideoTrackOptions(Tracks tracks) {
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

    static VideoTrackOption findClosestQuality(
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
}
