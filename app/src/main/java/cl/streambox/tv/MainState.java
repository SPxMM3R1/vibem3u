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
 * Estado compartido de la pantalla principal: constantes, campos y tipos anidados.
 * Capa de MainActivity (separada en la 0.6.03; el código se movió tal cual).
 */
@UnstableApi
abstract class MainState extends Activity {
    static final int SETTINGS_REQUEST = 1001;
    static final long OVERLAY_TIMEOUT_MS = 4_500;
    static final long PROGRAMME_DETAIL_TIMEOUT_MS = 12_000;
    static final long PLAYBACK_FREEZE_TIMEOUT_MS = 5_000L;
    /** Con bytes de video llegando, un corte se espera hasta 30 s antes de reiniciar (0.5.89). */
    static final long PLAYBACK_RECEIVING_WINDOW_MS = 4_000L;
    static final long PLAYBACK_MAX_WAIT_WHILE_RECEIVING_MS = 30_000L;
    final MediaTransferProgress mediaTransferProgress = new MediaTransferProgress();
    static final long PLAYBACK_DIAGNOSTIC_STALL_TIMEOUT_NS =
            PLAYBACK_FREEZE_TIMEOUT_MS * 1_000_000L;
    static final long PLAYBACK_WATCHDOG_INTERVAL_MS = 1_000L;
    static final long PLAYBACK_RECOVERY_COOLDOWN_MS = 4_000L;
    static final long RESOURCE_WARNING_VISIBLE_MS = 3_500L;
    static final long RESOURCE_WARNING_COOLDOWN_MS = 6_000L;
    static final String PLAYBACK_HEALTH_TAG = "VibeM3U-Playback";
    static final String RESOURCE_HEALTH_TAG = "VibeM3U-Resource";
    static final long UPDATE_CHECK_DELAY_MS = 4_000;
    static final long NO_RESOLUTION_REQUEST = -1L;
    static final String PLAYER_USER_AGENT = AppUserAgent.VALUE;

    final Handler mainHandler = new Handler(Looper.getMainLooper());
    final ExecutorService networkExecutor = idlePool(2, "vibem3u-network", false);
    // Resolver calls can spend several seconds in provider HTTP/TLS
    // handshakes. Keep them out of the executor that refreshes the playlist,
    // EPG and logos so maintenance work cannot delay channel startup.
    final ExecutorService playbackExecutor = idlePool(2, "vibem3u-playback", false);
    final ExecutorService logoCacheExecutor = idlePool(2, "vibem3u-logos", false);
    final ExecutorService resourceCacheExecutor = idlePool(1, "vibem3u-resources", false);
    /**
     * Guía (leer caché, descargar y mezclar ~3 MB de XML) y precarga de vecinos a baja prioridad:
     * no compiten con el arranque del video ni ocupan el hilo de red de la lista (0.5.85).
     */
    final ExecutorService epgExecutor = backgroundExecutor("vibem3u-epg");
    /**
     * 0.6.28: la combinación de guías va en su propio carril. En la cola de descargas
     * quedaba detrás de la revisión de red (y de procesar la guía nueva), así que la guía
     * de Lista 1 ya leída del disco tardaba en verse.
     */
    final ExecutorService epgMergeExecutor = backgroundExecutor("vibem3u-epg-merge");
    /** Momento del primer pedido de guía de esta carga (para medir cuándo se ve). */
    long epgRequestedAtMillis;
    final ExecutorService prefetchExecutor = backgroundExecutor("vibem3u-prefetch");
    static final long NEIGHBOR_PREFETCH_DELAY_MS = 5_000L;
    static final long NEIGHBOR_REWARM_MS = 4L * 60L * 1000L;
    final Runnable prefetchNeighbors = this::prefetchNeighborChannels;

    static ExecutorService backgroundExecutor(String name) {
        return idlePool(1, name, true);
    }

    /**
     * Grupo de hilos con nombre (0.6.01). Mismo tamaño y orden de tareas que antes
     * (newFixedThreadPool), pero los hilos sin trabajo se cierran a los 30 s: con siete
     * grupos eran hasta 12 hilos vivos toda la sesión aunque no hicieran nada.
     */
    static ExecutorService idlePool(int threads, String name, boolean background) {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(() -> {
                        if (background) {
                            android.os.Process.setThreadPriority(
                                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        }
                        runnable.run();
                    }, threads == 1 ? name : name + "-" + count.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    /** Catálogo, enlaces Highfly y variantes TvVoo: tres descargas en paralelo (0.5.85). */
    final ExecutorService catalogExecutor = idlePool(3, "vibem3u-catalog", false);
    boolean catalogRefreshRunning;
    /** Pausa del zapeo continuo antes de abrir el canal elegido (0.5.85). */
    static final long ZAP_SETTLE_MS = 300L;
    long lastZapKeyAtMs;
    boolean zapPreviewPending;
    /** Atajo 0.6.32: tres OK separados en menos de 700 ms vuelven al primer canal visible. */
    final RapidOkSequence rapidOkSequence = new RapidOkSequence();
    final Runnable commitZap = () -> {
        if (!this.zapPreviewPending) return;
        this.zapPreviewPending = false;
        playChannel(this.channelIndex);
    };
    /** Documento del catálogo ya aplicado; "" mientras no se aplica ninguno. */
    String appliedCatalogDocument = "";
    static final long CATALOG_REFRESH_INTERVAL_MS = 30L * 60L * 1000L;
    static final long CATALOG_REFRESH_ON_START_MS = 15L * 60L * 1000L;
    /** La app queda abierta días: el catálogo y los enlaces se renuevan cada 30 min. */
    final Runnable catalogRefreshTicker = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (!settingsOpen) refreshPublishedPlaybackCatalog();
            mainHandler.postDelayed(this, CATALOG_REFRESH_INTERVAL_MS);
        }
    };
    PlaylistRepository repository;
    EpgRepository epgRepository;
    final PlaybackRecoveryEpisode playbackRecoveryEpisode = new PlaybackRecoveryEpisode();
    final PlaybackStartupMetrics startupMetrics = new PlaybackStartupMetrics();
    PlaybackBufferManager playbackBufferManager;
    final ResolverCoordinator resolverCoordinator = new ResolverCoordinator();
    ResolverCatalogRepository resolverCatalogRepository;
    ResolverPreferences resolverPreferences;
    PublishedPlaybackCatalogRepository publishedPlaybackCatalogRepository;
    PublishedPlaybackCatalog publishedPlaybackCatalog = PublishedPlaybackCatalog.empty();
    boolean publishedCatalogRefreshPending;
    /**
     * Al arrancar, la lista no se muestra hasta tener el catálogo publicado: él trae
     * los canales de proveedor y la numeración final. Sin esto, la lista M3U aparecía
     * primero y un segundo después todos los canales se corrían de número.
     */
    static final long PUBLISHED_CATALOG_WAIT_MS = 6_000L;
    boolean publishedCatalogWaitExpired;
    final Runnable publishedCatalogWaitTimeout = () -> {
        // Sin catálogo a tiempo (sin red, servidor lento): se muestra lo que hay.
        publishedCatalogWaitExpired = true;
        applyPlaylistsAfterCatalogWait();
    };
    StreamResolverRegistry streamResolverRegistry;
    Map<String, Integer> resolverChannelCounts = Collections.emptyMap();
    final List<Channel> channels = new ArrayList<>();
    ChannelVisibility channelVisibility;
    /** Canales de adultos desbloqueados en esta tele, tal como se aplicó a la lista (0.6.36). */
    boolean adultChannelsUnlocked;
    final Map<Integer, Playlist> playlistsBySource = new LinkedHashMap<>();
    final Map<String, EpgData> epgDataByUrl = new LinkedHashMap<>();
    final Set<String> activeEpgUrls = new LinkedHashSet<>();
    final Set<String> epgRequests = new HashSet<>();
    /** Fuente de Lista 1 (la que se recuerda y cuya guía carga primero), 0.6.23. */
    static final int LIST_ONE_SOURCE = 1;
    /** Identidades (PlaybackPreferences.channelIdentity) de los canales de Lista 1. */
    final Set<String> listOneIdentities = new HashSet<>();
    /**
     * Guía por etapas (0.6.23): primero la de Lista 1 (caché y descarga); la de las otras
     * listas espera en {@link #deferredEpgUris} hasta que la de Lista 1 termine.
     */
    boolean listOneEpgReady;
    int listOneEpgInFlight;
    final List<URI> deferredEpgUris = new ArrayList<>();
    /** El canal preferido del arranque viene del último visto (no de un recordatorio). */
    boolean startupPreferredFromLastChannel;

    PlayerView playerView;
    PlaybackDiagnosticsWorker playbackBitrateMeter;
    View channelOverlay;
    View loadingPanel;
    TextView loadingText;
    /** «CONECTANDO» sobre el paso de carga (0.5.99, moderno). */
    TextView loadingKicker;
    /** Pantalla de error y aviso breve (0.5.99, moderno; null en el clásico no se usan). */
    View playbackErrorOverlay;
    TextView playbackErrorTitle;
    TextView playbackErrorEyebrow;
    TextView playbackErrorMessage;
    TextView playbackErrorTried;
    TextView playbackErrorCode;
    TextView playbackErrorClock;
    TextView playbackErrorDate;
    TextView playbackErrorHint;
    ImageView playbackErrorLogo;
    android.widget.Button playbackErrorRetry;
    android.widget.Button playbackErrorSecondary;
    /** true: falló la lista (Reintentar recarga, Opciones abre Opciones). */
    boolean playbackErrorIsPlaylist;
    /**
     * La señal falló con la Guía abierta (0.6.01): la pantalla de error se superponía a la
     * grilla. Queda pendiente y se muestra al cerrar la Guía, si nada la resolvió antes
     * (otro canal, la señal que vuelve o una lista nueva).
     */
    boolean playbackErrorDeferred;
    View noticeOverlay;
    TextView noticeText;
    TextView clock;
    View programmeDetailOverlay;
    TextView detailLive;
    TextView detailLabel;
    TextView detailTitle;
    TextView detailMeta;
    ProgressBar detailProgress;
    TextView detailDescription;
    View detailSide;
    TextView detailNextLabel;
    TextView detailNextTitle;
    TextView detailAfterLabel;
    TextView detailAfterTitle;
    /** Guía activa (moderna o clásica, según {@link UiStyle}). */
    View guideView;
    GuideSurface guideSurface;
    /** Estilo con que se creó esta pantalla; si cambia en Opciones, se recarga. */
    boolean classicUi;
    EpgGuideNavigator guideNavigator;
    /** Filas de la guía: índices en {@link #channels} según el filtro de categoría. */
    final List<Integer> guideRows = new ArrayList<>();
    final List<String> guideFilters = new ArrayList<>();
    int guideFilterIndex;
    boolean guideFiltersFocused;
    /** Logos ya cargados para la guía, por URI; solo se tocan en el hilo principal. */
    /**
     * Logos ya recortados de la guía (0.6.01): tope de 1/16 de la memoria de la app (entre 4 y
     * 16 MB). Antes era un HashMap sin límite; en teles de 1 GB recorrer la guía varias veces
     * podía terminar en falta de memoria. Un logo expulsado se vuelve a leer de la caché en disco.
     */
    final android.util.LruCache<String, android.graphics.Bitmap> guideLogos =
            new android.util.LruCache<String, android.graphics.Bitmap>(guideLogoCacheBytes()) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value.getAllocationByteCount();
                }

                @Override
                protected void entryRemoved(boolean evicted, String key,
                        android.graphics.Bitmap oldValue, android.graphics.Bitmap newValue) {
                    // Expulsado: la próxima vez que se dibuje se vuelve a pedir.
                    if (evicted) guideLogoRequests.remove(key);
                }
            };

    static int guideLogoCacheBytes() {
        long sixteenth = Runtime.getRuntime().maxMemory() / 16L;
        return (int) Math.max(4L << 20, Math.min(16L << 20, sixteenth));
    }
    final Set<String> guideLogoRequests = new HashSet<>();
    View sourceSelectorOverlay;
    TextView sourceSelectorTitle;
    TextView sourceSelectorChannel;
    TextView sourceSelectorStatus;
    LinearLayout sourceSelectorOptions;
    Button sourceSelectorCloseButton;
    ImageView channelLogo;
    TextView channelLogoFallback;
    /** Tamaño del logo en vivo (0.5.60). */
    View channelLogoFrame;
    View logoSizeAdjust;
    TextView logoSizeValue;
    boolean logoSizeAdjusting;
    /**
     * Moderno (0.5.63): el detalle con OK ya no es otra pantalla; el mismo OSD muestra la
     * descripción bajo el título (con logo y avance, sin «Ahora» ni «Después»).
     */
    boolean osdDescriptionOpen;
    /**
     * Respaldo TvVoo de un canal directo (0.5.64): activo para la reproducción actual, y la
     * identidad que debe seguir en respaldo cuando una recuperación vuelve a abrir el canal.
     */
    boolean tvvooBackupActive;
    String pendingTvVooBackupIdentity;
    /** Respaldo directo en uso (0 = el primero) mientras tvvooBackupActive; 0.5.72. */
    int directBackupIndex;
    /** -1 = la recuperación vuelve a la señal principal (tras fallar la recordada). */
    int pendingDirectBackupIndex;
    /** El canal abrió en la señal recordada del selector, no en la principal (0.5.75). */
    boolean rememberedBackupStart;
    /** La señal recordada falló y ya se volvió una vez a la principal. */
    boolean backupReturnedToPrincipal;
    boolean adjustLogoSizeAfterSettings;
    /** Sincronía del audio sobre el video (0.6.31). */
    boolean adjustAudioSyncAfterSettings;
    boolean audioSyncAdjusting;
    int audioSyncOriginalMs;
    int audioSyncAdjustMs;
    View audioSyncOverlay;
    TextView audioSyncValueView;
    String logoSizeKey;
    float logoSizeOriginal = 1f;
    float logoSizeScale = 1f;
    android.graphics.Bitmap displayedLogoBitmap;
    String displayedLogoKey;
    TextView channelNumber;
    TextView channelName;
    ContinuousMarqueeTextView contentTitle;
    TextView programmeTime;
    ProgressBar liveProgress;
    View osdHero;
    TextView osdDescription;
    TextView osdNext;
    /** Moderno (0.5.96): «● 033 · NOMBRE · CATEGORÍA» sobre el título; null en el clásico. */
    TextView osdEyebrow;
    TextView osdClockTime;
    TextView osdClockDate;
    View loadingProgress;
    TextView videoInfo;
    TextView codecInfo;
    TextView statusDot;
    TextView streamStatus;

    ExoPlayer player;
    AppUpdater appUpdater;
    PlaybackPreferences playbackPreferences;
    ChannelLogoCache channelLogoCache;
    EpgData epgData = EpgData.empty();
    String loadedPlaylistSignature = "";
    int channelIndex;
    boolean loadFailed;
    boolean settingsOpen;
    boolean restartPlaybackAfterFocusLoss;
    String resolverSettingsSnapshotBeforeSettings = "";
    String playlistSourcesSnapshotBeforeSettings = "";
    boolean refreshAfterSettings;
    /** Opciones › En reproducción pidió el selector de fuente; se abre al recuperar el foco. */
    boolean openSourceSelectorAfterSettings;
    long catalogUpdatedAtMillis;
    long epgUpdatedAtMillis;
    boolean overlayAwaitingPlayback;
    boolean exiting;
    boolean resourcesReleased;
    Dialog exitDialog;
    /** Escena de Highfly Premium abierta (token rechazado o vinculación). */
    Dialog premiumSceneDialog;
    /** Aviso «Mejor calidad disponible» (2026-10-03). */
    View qualityUpgradeOverlay;
    TextView qualityUpgradeTitle;
    TextView qualityUpgradeMessage;
    Button qualityUpgradeSwitch;
    Button qualityUpgradeDismiss;
    android.widget.ProgressBar qualityUpgradeTimer;
    android.animation.ObjectAnimator qualityUpgradeTimerAnimation;
    java.util.concurrent.Future<?> qualityUpgradeTask;
    ResolutionContext qualityUpgradeContext;
    long qualityUpgradeCheckedGeneration = -1L;
    ResolvedPlaybackSource qualityUpgradeOffer;
    /** Fuente anterior a un cambio de calidad: si la nueva falla pronto, se vuelve a ella. */
    ResolvedPlaybackSource qualityRevertSource;
    long qualityRevertUntilElapsedRealtime;
    /** Inicio de la fuente actual: los enlaces Clean de TvVoo vencen a los ~20 min. */
    long currentSourceStartedElapsedRealtime;
    /** Sin internet: la reconexión espera a que vuelva la red, sin gastar intentos. */
    ConnectivityManager.NetworkCallback networkWaitCallback;
    boolean waitingForNetwork;
    long tvvooNoSignalToken;
    String qualityPreferenceAppliedFor;
    String subtitlePreferenceAppliedFor;
    /** Opciones de audio que vienen de la tabla PMT (no de Media3): «pmt|en». */
    static final String TS_AUDIO_KEY_PREFIX = "pmt|";
    /** Audio MPEG-TS de la fuente actual (0.6.12): idiomas que trae y el que se aplicó. */
    TsAudioSession currentTsAudioSession;
    String subtitleTextObservedFor;
    int playlistGeneration;
    long logoRequestGeneration;
    Future<?> logoRequestTask;
    String displayedLogoIdentity = "";
    final Set<String> logoRevalidatedThisSession = new HashSet<>();
    boolean playerUsesVolumeNormalization;
    boolean playerUsesSoftwareMp2;
    boolean playerUsesTunneling;
    int tunneledRenderedFrames;
    long tunneledFrameProgressNs;
    int tunnelFpsCount;
    long tunnelFpsSinceMs;
    float tunneledFps;
    long playbackGeneration;
    boolean playbackWatchdogScheduled;
    long playbackLoadingSinceElapsedRealtime = -1L;
    boolean playbackHasStarted;
    long playbackRecoveryCooldownUntilElapsedRealtime;
    boolean playbackAutoRecoveryInFlight;
    final PlaybackRecoveryBudget playbackRecoveryBudget = new PlaybackRecoveryBudget();
    long playbackRecoveryToken;
    boolean playbackRecoveryFailed;
    int playbackSourceRecoveryAttempts;
    boolean playbackSourceRecoveryInFlight;
    long playbackSourceStabilityToken;
    String playbackSourceStabilityScheduledFor = "";
    long lastPlaybackDiagnosticCode;
    Future<?> playbackResolutionTask;
    ResolutionContext playbackResolutionContext;
    Future<?> sourceCandidateTask;
    ResolutionContext sourceCandidateContext;
    long sourceCandidateRequestId;
    final List<ResolvedPlaybackCandidate> sourceCandidates = new ArrayList<>();
    final List<PlaybackOption> playbackOptions = new ArrayList<>();
    final List<View> sourceCandidateViews = new ArrayList<>();
    int sourceCandidateFocusIndex = -1;
    boolean sourceSelectorCloseFocused;
    ManifestHandoffCache playbackManifestCache;
    long playbackResolutionRequestId;
    long activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    Channel playbackChannel;
    ResolvedPlaybackSource currentPlaybackSource;
    String loadingMessageBase = "";
    boolean loadingMessageAnimating;
    boolean loadingAnimationScheduled;
    int loadingDotCount;
    boolean resourceWarningVisible;
    long resourceWarningUntilElapsedRealtime;
    long resourceWarningCooldownUntilElapsedRealtime;
    boolean bufferWarningConditionActive;
    int memoryWarningSamples;
    PlaybackResourceWarningPolicy.Type memoryWarningCondition =
            PlaybackResourceWarningPolicy.Type.NONE;
    boolean startupSelectionPending;
    String startupPreferredChannelIdentity = "";
    /** Canal pedido por el botón «Ver» de un recordatorio; se abre al tener canales. */
    String pendingReminderChannelIdentity = "";
    /** Ids de los recordatorios vigentes, para dibujar la campana en la Guía. */
    final Set<String> reminderIds = new HashSet<>();
    boolean guideOkDown;
    boolean guideOkLongHandled;
    String epgMergeInputSignature = "";
    long epgMergeGeneration;
    boolean playbackDiagnosticsActive;
    final AtomicBoolean diagnosticsUpdateQueued = new AtomicBoolean();
    final Runnable applyMeasuredDiagnostics = () -> {
        diagnosticsUpdateQueued.set(false);
        if (exiting || resourcesReleased || player == null) return;
        maybeActivatePlaybackDiagnostics();
        updateDiagnostics();
    };

    final Runnable hideOverlay = () -> {
        channelOverlay.setVisibility(View.GONE);
        clock.setVisibility(View.GONE);
        updateDiagnosticsVisibility();
    };
    final Runnable hideProgrammeDetail = () -> {
        if (programmeDetailOverlay != null) {
            programmeDetailOverlay.setVisibility(View.GONE);
        }
        if (osdHero != null) osdHero.setVisibility(View.VISIBLE);
        if (osdDescriptionOpen) {
            osdDescriptionOpen = false;
            updateProgrammeInfo();
        }
    };
    /** Timeout del detalle: se cierra junto con el OSD que lo acompaña. */
    final Runnable hideProgrammeDetailWithOverlay = () -> {
        hideProgrammeDetail.run();
        hideOverlay.run();
    };
    final Runnable updateClock = new Runnable() {
        @Override public void run() {
            Date nowDate = new Date();
            String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(nowDate);
            if (classicUi) {
                clock.setText(currentTime);
            } else if (osdClockTime != null && osdClockDate != null) {
                String date = new SimpleDateFormat("EEEE d 'de' MMMM", Locale.forLanguageTag("es-CL"))
                        .format(nowDate);
                if (!date.isEmpty()) {
                    date = date.substring(0, 1).toUpperCase(Locale.ROOT) + date.substring(1);
                }
                osdClockTime.setText(currentTime);
                osdClockDate.setText(date);
                if (isPlaybackErrorVisible() && playbackErrorClock != null) {
                    playbackErrorClock.setText(currentTime);
                    playbackErrorDate.setText(date);
                }
            }
            // La línea de la hora actual de la Guía avanza con el reloj.
            if (isGuideVisible()) guideSurface.refresh(System.currentTimeMillis());
            mainHandler.postDelayed(this, 30_000);
        }
    };
    final Runnable updateProgramme = new Runnable() {
        @Override public void run() {
            updateProgrammeInfo();
            if (!exiting && !isFinishing()) {
                mainHandler.postDelayed(this, 30_000);
            }
        }
    };
    final Runnable playbackWatchdog = new Runnable() {
        @Override public void run() {
            playbackWatchdogScheduled = false;
            if (exiting || resourcesReleased || isFinishing()) return;
            checkPlaybackHealth();
            schedulePlaybackWatchdog();
        }
    };
    final Runnable animateLoadingText = new Runnable() {
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
    final Runnable hideResourceWarning = new Runnable() {
        @Override public void run() {
            if (!resourceWarningVisible) return;
            long remaining = resourceWarningUntilElapsedRealtime
                    - SystemClock.elapsedRealtime();
            if (remaining > 0L) {
                mainHandler.postDelayed(this, remaining);
                return;
            }
            resourceWarningVisible = false;
            resourceWarningUntilElapsedRealtime = 0L;
            hideLoadingState();
        }
    };

    static final class PlaylistRefreshState {
        final int generation;
        final String sourceSignature;
        final Map<Integer, Playlist> latest;
        final Set<Integer> completedNetworkPositions = new HashSet<>();
        int pendingCacheReads;
        int pendingNetwork;
        Throwable firstError;

        PlaylistRefreshState(
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

        boolean isComplete() {
            return pendingCacheReads <= 0 && pendingNetwork <= 0;
        }
    }
    static final class PlaylistNetworkResult {
        final PlaylistSource source;
        final Playlist playlist;
        final boolean changed;
        final Throwable error;

        PlaylistNetworkResult(
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

        static PlaylistNetworkResult success(
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

        static PlaylistNetworkResult failure(PlaylistSource source, Throwable error) {
            return new PlaylistNetworkResult(source, null, false, error);
        }
    }
    /** One row in the shared source/quality selector. */
    static final class PlaybackOption {
        final String label;
        final String detail;
        final ResolvedPlaybackCandidate sourceCandidate;
        final VideoTrackOption quality;
        final boolean automatic;
        final boolean selected;

        PlaybackOption(
                String label,
                String detail,
                ResolvedPlaybackCandidate sourceCandidate,
                VideoTrackOption quality,
                boolean automatic,
                boolean selected
        ) {
            this.label = label;
            this.detail = detail;
            this.sourceCandidate = sourceCandidate;
            this.quality = quality;
            this.automatic = automatic;
            this.selected = selected;
        }

        static PlaybackOption source(
                ResolvedPlaybackCandidate candidate,
                boolean selected
        ) {
            return new PlaybackOption(
                    candidate.getLabel(),
                    candidate.getDetail(),
                    candidate,
                    null,
                    false,
                    selected
            );
        }

        static PlaybackOption automatic(boolean selected) {
            return new PlaybackOption(
                    "Automático",
                    "Calidad adaptativa según la red y el reproductor.",
                    null,
                    null,
                    true,
                    selected
            );
        }

        static PlaybackOption quality(VideoTrackOption quality, boolean selected) {
            return new PlaybackOption(
                    quality.label(),
                    "Pista de video disponible en el stream.",
                    null,
                    quality,
                    false,
                    selected
            );
        }

        boolean isQuality() {
            return automatic || quality != null;
        }
    }
    static final class VideoTrackOption {
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

    // Métodos que una capa usa y otra de más arriba implementa (separación de la 0.6.03):
    // MainState ← MainPlayback ← MainOverlays ← MainGuide ← MainSelector ← MainActivity.
    abstract void prefetchNeighborChannels();
    abstract void playChannel(int requestedIndex);
    abstract void playChannel(int requestedIndex, boolean revalidateLogo);
    abstract void refreshPublishedPlaybackCatalog();
    abstract void applyPlaylistsAfterCatalogWait();
    abstract void maybeActivatePlaybackDiagnostics();
    abstract void updateDiagnostics();
    abstract void updateDiagnosticsVisibility();
    abstract void updateProgrammeInfo();
    abstract boolean isPlaybackErrorVisible();
    abstract boolean isGuideVisible();
    abstract void checkPlaybackHealth();
    abstract void schedulePlaybackWatchdog();
    abstract void renderAnimatedLoadingText();
    abstract void hideLoadingState();
    abstract boolean isVolumeNormalizationEnabled();

    abstract boolean isSoftwareMp2Enabled();
    abstract boolean isVideoTunnelingEnabled();
    abstract void showLoadingState(String message);
    abstract void markPublishedHighflyLinkFailed();
    abstract void setStatus(String text, int colorResource);
    abstract void showOverlay(boolean keepVisible);
    abstract void hidePlaybackErrorScreen();
    abstract void bindOsdChannel(int displayNumber, Channel channel);
    abstract void loadChannelLogo(Channel channel, boolean revalidate);
    abstract void showOverlayForChannelStart();
    abstract void closePlaybackSourceSelector();
    abstract void resetResourceWarningState();
    abstract void updateResourceWarnings(int playbackState);
    abstract boolean isAutoReconnectEnabled();
    abstract boolean isNetworkAvailable();
    abstract void showResolutionProgress(ResolutionProgress progress);
    abstract boolean isSourceSelectorVisible();
    abstract void showPlaybackErrorScreen();
    abstract void refreshReminderIds();
    abstract void markBackConsumedByPanel();
    abstract void openSettings();
    abstract void openSettings(int initialTab);
    abstract List<PlaylistSource> getPlaylistSources();
    abstract void openPlaybackSourceSelector();
    abstract void refreshPlaylists(List<PlaylistSource> configuredSources);
    abstract void reapplyChannelList();

    static URI failedRequestUri(Throwable error) {
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

    static String shortMessage(Throwable error) {
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
        if (message == null || AppStrings.isBlank(message)) return "Error desconocido.";
        return SafePlaybackText.detail(message);
    }

    static int httpResponseCode(Throwable error) {
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

    static int findChannelIndexByIdentity(List<Channel> candidates, String identity) {
        if (candidates == null || identity == null || AppStrings.isBlank(identity)) return -1;
        for (int index = 0; index < candidates.size(); index++) {
            if (identity.equals(PlaybackPreferences.channelIdentity(candidates.get(index)))) {
                return index;
            }
        }
        return -1;
    }

    static String trimDecimal(float value) {
        return value == Math.round(value)
                ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    static String codecName(String mimeType) {
        if (mimeType == null) return "—";
        return switch (mimeType) {
            case MimeTypes.VIDEO_H264 -> "H.264";
            case MimeTypes.VIDEO_H265 -> "H.265";
            case MimeTypes.VIDEO_AV1 -> "AV1";
            case MimeTypes.VIDEO_VP9 -> "VP9";
            case MimeTypes.AUDIO_AAC -> "AAC";
            case MimeTypes.AUDIO_AC3 -> "AC-3";
            case MimeTypes.AUDIO_E_AC3 -> "E-AC-3";
            case MimeTypes.AUDIO_MPEG_L2 -> "MP2";
            case MimeTypes.AUDIO_OPUS -> "Opus";
            default -> mimeType.substring(mimeType.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        };
    }

    static String initials(String name) {
        if (name == null || AppStrings.isBlank(name)) return "TV";
        StringBuilder result = new StringBuilder(2);
        for (String word : name.trim().split("\\s+")) {
            if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0)));
            if (result.length() == 2) break;
        }
        return result.length() == 0 ? "TV" : result.toString();
    }
    static final int NOTICE_OK = 0;
    static final int NOTICE_REMINDER = 3;
    static final int NOTICE_WARN = 1;
    static final int NOTICE_PROGRESS = 2;
    final Runnable hideNotice = () -> {
        if (noticeOverlay == null) return;
        noticeOverlay.animate().cancel();
        noticeOverlay.animate().alpha(0f).setDuration(220L)
                .withEndAction(() -> noticeOverlay.setVisibility(View.GONE)).start();
    };

    static boolean isChannelNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN;
    }

}
