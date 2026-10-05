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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class MainActivity extends Activity {
    private static final int SETTINGS_REQUEST = 1001;
    private static final long OVERLAY_TIMEOUT_MS = 4_500;
    private static final long PROGRAMME_DETAIL_TIMEOUT_MS = 12_000;
    private static final long PLAYBACK_FREEZE_TIMEOUT_MS = 5_000L;
    private static final long PLAYBACK_DIAGNOSTIC_STALL_TIMEOUT_NS =
            PLAYBACK_FREEZE_TIMEOUT_MS * 1_000_000L;
    private static final long PLAYBACK_WATCHDOG_INTERVAL_MS = 1_000L;
    private static final long PLAYBACK_RECOVERY_COOLDOWN_MS = 4_000L;
    private static final long RESOURCE_WARNING_VISIBLE_MS = 3_500L;
    private static final long RESOURCE_WARNING_COOLDOWN_MS = 6_000L;
    private static final String PLAYBACK_HEALTH_TAG = "VibeM3U-Playback";
    private static final String RESOURCE_HEALTH_TAG = "VibeM3U-Resource";
    private static final long UPDATE_CHECK_DELAY_MS = 4_000;
    private static final long NO_RESOLUTION_REQUEST = -1L;
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
    private final PlaybackRecoveryEpisode playbackRecoveryEpisode = new PlaybackRecoveryEpisode();
    private final PlaybackStartupMetrics startupMetrics = new PlaybackStartupMetrics();
    private PlaybackBufferManager playbackBufferManager;
    private final ResolverCoordinator resolverCoordinator = new ResolverCoordinator();
    private ResolverCatalogRepository resolverCatalogRepository;
    private ResolverPreferences resolverPreferences;
    private PublishedPlaybackCatalogRepository publishedPlaybackCatalogRepository;
    private PublishedPlaybackCatalog publishedPlaybackCatalog = PublishedPlaybackCatalog.empty();
    private boolean publishedCatalogRefreshPending;
    /**
     * Al arrancar, la lista no se muestra hasta tener el catálogo publicado: él trae
     * los canales de proveedor y la numeración final. Sin esto, la lista M3U aparecía
     * primero y un segundo después todos los canales se corrían de número.
     */
    private static final long PUBLISHED_CATALOG_WAIT_MS = 6_000L;
    private boolean publishedCatalogWaitExpired;
    private final Runnable publishedCatalogWaitTimeout = () -> {
        // Sin catálogo a tiempo (sin red, servidor lento): se muestra lo que hay.
        publishedCatalogWaitExpired = true;
        applyPlaylistsAfterCatalogWait();
    };
    private StreamResolverRegistry streamResolverRegistry;
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
    private View programmeDetailOverlay;
    private TextView detailLive;
    private TextView detailLabel;
    private TextView detailTitle;
    private TextView detailMeta;
    private ProgressBar detailProgress;
    private TextView detailDescription;
    private View detailSide;
    private TextView detailNextLabel;
    private TextView detailNextTitle;
    private TextView detailAfterLabel;
    private TextView detailAfterTitle;
    /** Guía activa (moderna o clásica, según {@link UiStyle}). */
    private View guideView;
    private GuideSurface guideSurface;
    /** Estilo con que se creó esta pantalla; si cambia en Opciones, se recarga. */
    private boolean classicUi;
    private EpgGuideNavigator guideNavigator;
    /** Filas de la guía: índices en {@link #channels} según el filtro de categoría. */
    private final List<Integer> guideRows = new ArrayList<>();
    private final List<String> guideFilters = new ArrayList<>();
    private int guideFilterIndex;
    private boolean guideFiltersFocused;
    /** Logos ya cargados para la guía, por URI; solo se tocan en el hilo principal. */
    private final java.util.Map<String, android.graphics.Bitmap> guideLogos = new java.util.HashMap<>();
    private final Set<String> guideLogoRequests = new HashSet<>();
    private View sourceSelectorOverlay;
    private TextView sourceSelectorTitle;
    private TextView sourceSelectorChannel;
    private TextView sourceSelectorStatus;
    private LinearLayout sourceSelectorOptions;
    private Button sourceSelectorCloseButton;
    private ImageView channelLogo;
    private TextView channelLogoFallback;
    /** Tamaño del logo en vivo (0.5.60). */
    private View channelLogoFrame;
    private View logoSizeAdjust;
    private TextView logoSizeValue;
    private boolean logoSizeAdjusting;
    /**
     * Moderno (0.5.63): el detalle con OK ya no es otra pantalla; el mismo OSD muestra la
     * descripción bajo el título (con logo y avance, sin «Ahora» ni «Después»).
     */
    private boolean osdDescriptionOpen;
    /**
     * Respaldo TvVoo de un canal directo (0.5.64): activo para la reproducción actual, y la
     * identidad que debe seguir en respaldo cuando una recuperación vuelve a abrir el canal.
     */
    private boolean tvvooBackupActive;
    private String pendingTvVooBackupIdentity;
    /** Respaldo directo en uso (0 = el primero) mientras tvvooBackupActive; 0.5.72. */
    private int directBackupIndex;
    private int pendingDirectBackupIndex;
    private boolean adjustLogoSizeAfterSettings;
    private String logoSizeKey;
    private float logoSizeOriginal = 1f;
    private float logoSizeScale = 1f;
    private android.graphics.Bitmap displayedLogoBitmap;
    private String displayedLogoKey;
    private TextView channelNumber;
    private TextView channelName;
    private ContinuousMarqueeTextView contentTitle;
    private TextView programmeTime;
    private ProgressBar liveProgress;
    private View osdHero;
    private TextView osdDescription;
    private TextView osdNext;
    private TextView osdClockTime;
    private TextView osdClockDate;
    private View loadingProgress;
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
    private boolean restartPlaybackAfterFocusLoss;
    private String resolverSettingsSnapshotBeforeSettings = "";
    private String playlistSourcesSnapshotBeforeSettings = "";
    private boolean refreshAfterSettings;
    /** Opciones › En reproducción pidió el selector de fuente; se abre al recuperar el foco. */
    private boolean openSourceSelectorAfterSettings;
    private long catalogUpdatedAtMillis;
    private long epgUpdatedAtMillis;
    private boolean overlayAwaitingPlayback;
    private boolean exiting;
    private boolean resourcesReleased;
    private Dialog exitDialog;
    /** Escena de Highfly Premium abierta (token rechazado o vinculación). */
    private Dialog premiumSceneDialog;
    /** Aviso «Mejor calidad disponible» (2026-10-03). */
    private View qualityUpgradeOverlay;
    private TextView qualityUpgradeTitle;
    private TextView qualityUpgradeMessage;
    private Button qualityUpgradeSwitch;
    private Button qualityUpgradeDismiss;
    private android.widget.ProgressBar qualityUpgradeTimer;
    private android.animation.ObjectAnimator qualityUpgradeTimerAnimation;
    private java.util.concurrent.Future<?> qualityUpgradeTask;
    private ResolutionContext qualityUpgradeContext;
    private long qualityUpgradeCheckedGeneration = -1L;
    private ResolvedPlaybackSource qualityUpgradeOffer;
    /** Fuente anterior a un cambio de calidad: si la nueva falla pronto, se vuelve a ella. */
    private ResolvedPlaybackSource qualityRevertSource;
    private long qualityRevertUntilElapsedRealtime;
    /** Inicio de la fuente actual: los enlaces Clean de TvVoo vencen a los ~20 min. */
    private long currentSourceStartedElapsedRealtime;
    /** Sin internet: la reconexión espera a que vuelva la red, sin gastar intentos. */
    private ConnectivityManager.NetworkCallback networkWaitCallback;
    private boolean waitingForNetwork;
    private long tvvooNoSignalToken;
    private String qualityPreferenceAppliedFor;
    private String subtitlePreferenceAppliedFor;
    private String subtitleTextObservedFor;
    private int playlistGeneration;
    private long logoRequestGeneration;
    private Future<?> logoRequestTask;
    private String displayedLogoIdentity = "";
    private final Set<String> logoRevalidatedThisSession = new HashSet<>();
    private boolean playerUsesVolumeNormalization;
    private boolean playerUsesCncBuffering;
    private long playbackGeneration;
    private boolean playbackWatchdogScheduled;
    private long playbackLoadingSinceElapsedRealtime = -1L;
    private boolean playbackHasStarted;
    private long playbackRecoveryCooldownUntilElapsedRealtime;
    private boolean playbackAutoRecoveryInFlight;
    private final PlaybackRecoveryBudget playbackRecoveryBudget = new PlaybackRecoveryBudget();
    private long playbackRecoveryToken;
    private boolean playbackRecoveryFailed;
    private int playbackSourceRecoveryAttempts;
    private boolean playbackSourceRecoveryInFlight;
    private long playbackSourceStabilityToken;
    private String playbackSourceStabilityScheduledFor = "";
    private long lastPlaybackDiagnosticCode;
    private Future<?> playbackResolutionTask;
    private ResolutionContext playbackResolutionContext;
    private Future<?> sourceCandidateTask;
    private ResolutionContext sourceCandidateContext;
    private long sourceCandidateRequestId;
    private final List<ResolvedPlaybackCandidate> sourceCandidates = new ArrayList<>();
    private final List<PlaybackOption> playbackOptions = new ArrayList<>();
    private final List<View> sourceCandidateViews = new ArrayList<>();
    private int sourceCandidateFocusIndex = -1;
    private boolean sourceSelectorCloseFocused;
    private ManifestHandoffCache playbackManifestCache;
    private long playbackResolutionRequestId;
    private long activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    private Channel playbackChannel;
    private ResolvedPlaybackSource currentPlaybackSource;
    private String loadingMessageBase = "";
    private boolean loadingMessageAnimating;
    private boolean loadingAnimationScheduled;
    private int loadingDotCount;
    private boolean resourceWarningVisible;
    private long resourceWarningUntilElapsedRealtime;
    private long resourceWarningCooldownUntilElapsedRealtime;
    private boolean bufferWarningConditionActive;
    private int memoryWarningSamples;
    private PlaybackResourceWarningPolicy.Type memoryWarningCondition =
            PlaybackResourceWarningPolicy.Type.NONE;
    private boolean startupSelectionPending;
    private String startupPreferredChannelIdentity = "";
    /** Canal pedido por el botón «Ver» de un recordatorio; se abre al tener canales. */
    private String pendingReminderChannelIdentity = "";
    /** Ids de los recordatorios vigentes, para dibujar la campana en la Guía. */
    private final Set<String> reminderIds = new HashSet<>();
    private boolean guideOkDown;
    private boolean guideOkLongHandled;
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
    private final Runnable hideProgrammeDetail = () -> {
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
    private final Runnable hideProgrammeDetailWithOverlay = () -> {
        hideProgrammeDetail.run();
        hideOverlay.run();
    };
    private final Runnable updateClock = new Runnable() {
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
            }
            // La línea de la hora actual de la Guía avanza con el reloj.
            if (isGuideVisible()) guideSurface.refresh(System.currentTimeMillis());
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
    private final Runnable playbackWatchdog = new Runnable() {
        @Override public void run() {
            playbackWatchdogScheduled = false;
            if (exiting || resourcesReleased || isFinishing()) return;
            checkPlaybackHealth();
            schedulePlaybackWatchdog();
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
    private final Runnable hideResourceWarning = new Runnable() {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        classicUi = UiStyle.beginStart(this);
        consumeReminderIntent(getIntent());
        // Android borra las alarmas si la app fue detenida: se reponen al abrirla.
        ReminderAlerts.rescheduleAll(this);
        setContentView(R.layout.activity_main);
        SettingsActivity.ensureDefaultPlaylistConfigured(this);
        repository = new PlaylistRepository(this);
        epgRepository = new EpgRepository(this);
        channelLogoCache = new ChannelLogoCache(this);
        LogoScales.load(this);
        playbackPreferences = new PlaybackPreferences(this);
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
                    resolverPreferences
            );
        } catch (Exception ignored) {
            // The two original exact-ID resolvers remain available even if a
            // local catalogue update was interrupted or became incompatible.
            streamResolverRegistry = new StreamResolverRegistry();
        }
    }

    private void bindViews() {
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
        osdClockTime = findViewById(R.id.osd_clock_time);
        osdClockDate = findViewById(R.id.osd_clock_date);
        loadingProgress = findViewById(R.id.loading_progress);
        videoInfo = findViewById(R.id.video_info);
        codecInfo = findViewById(R.id.codec_info);
        statusDot = findViewById(R.id.status_dot);
        streamStatus = findViewById(R.id.stream_status);
        // Al final: el estilo clásico toca vistas del selector que se buscan más arriba.
        if (classicUi) applyClassicChrome();
    }

    private void createPlayer() {
        if (playbackBufferManager != null) playbackBufferManager.close();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        playbackBufferManager = new PlaybackBufferManager(Runtime.getRuntime().maxMemory(),
                activityManager != null && activityManager.isLowRamDevice(), playerUsesCncBuffering);
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
                if (isPlaying) maybeSchedulePlaybackSourceStability();
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                maybeActivatePlaybackDiagnostics();
                updateStreamStatus(playbackState);
                updateDiagnostics();
                if (playbackState == Player.STATE_READY && !loadFailed) {
                    if (hasRenderedVideoFrame()) {
                        playbackHasStarted = true;
                        playbackLoadingSinceElapsedRealtime = -1L;
                        maybeSchedulePlaybackSourceStability();
                        maybeScheduleQualityUpgradeCheck();
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

    private void refreshPlaylists(List<PlaylistSource> configuredSources) {
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
            startupPreferredChannelIdentity = AppStrings.isBlank(pendingReminderChannelIdentity)
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
            return pendingCacheReads <= 0 && pendingNetwork <= 0;
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
                epgUpdatedAtMillis = System.currentTimeMillis();
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
            if (AppStrings.isBlank(startupPreferredChannelIdentity)) {
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

    private void consumeReminderIntent(Intent intent) {
        if (intent == null) return;
        String identity = intent.getStringExtra(ReminderAlerts.EXTRA_OPEN_CHANNEL_IDENTITY);
        if (!AppStrings.isBlank(identity)) {
            pendingReminderChannelIdentity = identity;
            intent.removeExtra(ReminderAlerts.EXTRA_OPEN_CHANNEL_IDENTITY);
        }
    }

    /** «Ver» de un recordatorio: cambia al canal apenas la lista está cargada. */
    private void applyPendingReminderChannel() {
        if (AppStrings.isBlank(pendingReminderChannelIdentity) || channels.isEmpty()) return;
        int index = findChannelIndexByIdentity(channels, pendingReminderChannelIdentity);
        pendingReminderChannelIdentity = "";
        if (index < 0) return;
        closeGuide();
        // Si ya es el canal actual, se deja como está (puede estar cargando).
        if (index != channelIndex) playChannel(index);
        showOverlay(false);
    }

    private static int findChannelIndexByIdentity(List<Channel> candidates, String identity) {
        if (candidates == null || identity == null || AppStrings.isBlank(identity)) return -1;
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

    private List<Channel> buildOrderedChannelList(Map<Integer, Playlist> playlists) {
        List<Channel> result = new ArrayList<>();
        for (Map.Entry<Integer, Playlist> entry : orderedPlaylistEntries(playlists)) {
            Playlist playlist = entry.getValue();
            if (playlist != null) result.addAll(playlist.getChannels());
        }
        List<Channel> publishedProviderChannels = publishedPlaybackCatalog.activeProviderChannels();
        result = new ArrayList<>(TvVooChannelMerge.merge(
                result,
                publishedProviderChannels
        ));
        result = new ArrayList<>(HighflyChannelMerge.merge(
                result,
                publishedProviderChannels
        ));
        return publishedPlaybackCatalog.applyToPlayback(result);
    }

    private boolean isWaitingForPublishedCatalog() {
        return publishedCatalogRefreshPending && !publishedCatalogWaitExpired && channels.isEmpty();
    }

    /** Muestra la lista guardada mientras se esperaba el catálogo, si aún no hay canales. */
    private void applyPlaylistsAfterCatalogWait() {
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

    private boolean hasPublishedProviderChannels() {
        return publishedPlaybackCatalog != null
                && publishedPlaybackCatalog.hasActiveProviderChannels();
    }

    private void showPlaylistError(String detail) {
        loadFailed = true;
        clearResourceWarning();
        stopLoadingTextAnimation();
        loadingPanel.setVisibility(View.VISIBLE);
        if (!classicUi && loadingProgress != null) loadingProgress.setVisibility(View.GONE);
        loadingText.animate().cancel();
        loadingText.setAlpha(1f);
        loadingText.setTranslationY(0f);
        String message = getString(R.string.playlist_error);
        if (detail != null && !AppStrings.isBlank(detail)) {
            message += " · " + SafePlaybackText.detail(detail.replace('\n', ' '));
        }
        loadingText.setText(message);
    }

    private void clearResourceWarning() {
        mainHandler.removeCallbacks(hideResourceWarning);
        resourceWarningVisible = false;
        resourceWarningUntilElapsedRealtime = 0L;
    }

    private void resetResourceWarningState() {
        clearResourceWarning();
        resourceWarningCooldownUntilElapsedRealtime = 0L;
        bufferWarningConditionActive = false;
        memoryWarningSamples = 0;
        memoryWarningCondition = PlaybackResourceWarningPolicy.Type.NONE;
    }

    private boolean showResourceWarning(
            PlaybackResourceWarningPolicy.Type type,
            PlaybackBufferManager.Snapshot snapshot,
            int memoryPressureLevel
    ) {
        if (type == PlaybackResourceWarningPolicy.Type.NONE
                || playbackChannel == null
                || !playbackHasStarted
                || settingsOpen
                || loadFailed
                || !hasWindowFocus()
                || playbackAutoRecoveryInFlight
                || playbackRecoveryFailed) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < resourceWarningCooldownUntilElapsedRealtime) return false;

        int messageId;
        switch (type) {
            case BUFFER:
                messageId = R.string.resource_warning_buffer;
                break;
            case MEMORY_CRITICAL:
                messageId = R.string.resource_warning_memory_critical;
                break;
            case MEMORY:
                messageId = R.string.resource_warning_memory;
                break;
            default:
                return false;
        }

        clearResourceWarning();
        showLoadingState(getString(messageId));
        resourceWarningVisible = true;
        resourceWarningUntilElapsedRealtime = now + RESOURCE_WARNING_VISIBLE_MS;
        resourceWarningCooldownUntilElapsedRealtime =
                now + RESOURCE_WARNING_COOLDOWN_MS;
        mainHandler.postDelayed(hideResourceWarning, RESOURCE_WARNING_VISIBLE_MS);

        Log.w(
                RESOURCE_HEALTH_TAG,
                "warning type=" + type
                        + " bufferedMs=" + snapshot.bufferedDurationMs
                        + " heap=" + snapshot.heapPercent() + "%"
                        + " allocator=" + snapshot.allocatorPercent() + "%"
                        + " pressure=" + memoryPressureLevel
        );
        return true;
    }

    private void updateResourceWarnings(int playbackState) {
        if (player == null || playbackChannel == null || settingsOpen || loadFailed
                || !playbackHasStarted || playbackResolutionTask != null
                || playbackAutoRecoveryInFlight || playbackRecoveryFailed) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        boolean userPaused = !player.getPlayWhenReady() && !player.isPlaying();
        if (userPaused) {
            bufferWarningConditionActive = false;
            memoryWarningSamples = 0;
            memoryWarningCondition = PlaybackResourceWarningPolicy.Type.NONE;
            return;
        }
        boolean loading = playbackState == Player.STATE_BUFFERING
                || playbackState == Player.STATE_IDLE
                || (playbackState == Player.STATE_READY && player.isLoading());
        PlaybackBufferManager.Snapshot snapshot = playbackBufferManager == null
                ? PlaybackBufferManager.Snapshot.EMPTY : playbackBufferManager.snapshot();

        boolean bufferWarning = PlaybackResourceWarningPolicy.isBufferWarning(
                playbackHasStarted,
                userPaused,
                loading,
                snapshot.bufferedDurationMs,
                playbackLoadingSinceElapsedRealtime,
                now
        );
        if (!bufferWarning) {
            bufferWarningConditionActive = false;
        } else if (!bufferWarningConditionActive
                && showResourceWarning(
                PlaybackResourceWarningPolicy.Type.BUFFER,
                snapshot,
                0
        )) {
            bufferWarningConditionActive = true;
        }

        PlaybackResourceWarningPolicy.Type memoryType =
                PlaybackResourceWarningPolicy.memoryType(
                        playbackHasStarted,
                        0,
                        snapshot.heapUsedBytes,
                        snapshot.heapMaxBytes,
                        snapshot.allocatedBytes,
                        snapshot.targetBytes
                );
        if (memoryType == PlaybackResourceWarningPolicy.Type.NONE) {
            memoryWarningSamples = 0;
            memoryWarningCondition = PlaybackResourceWarningPolicy.Type.NONE;
        } else {
            memoryWarningSamples = Math.min(2, memoryWarningSamples + 1);
        }
        if (memoryType != PlaybackResourceWarningPolicy.Type.NONE
                && memoryWarningSamples >= 2
                && (memoryWarningCondition == PlaybackResourceWarningPolicy.Type.NONE
                || (memoryType == PlaybackResourceWarningPolicy.Type.MEMORY_CRITICAL
                && memoryWarningCondition != PlaybackResourceWarningPolicy.Type.MEMORY_CRITICAL))) {
            if (showResourceWarning(memoryType, snapshot, 0)) {
                memoryWarningCondition = memoryType;
            }
        }
    }

    private void handleMemoryPressure(int level) {
        if (playbackBufferManager != null) playbackBufferManager.onMemoryPressure(level);
        if (player == null || playbackChannel == null || !playbackHasStarted
                || settingsOpen || loadFailed || !hasWindowFocus()) return;
        if (!player.getPlayWhenReady() && !player.isPlaying()) return;

        PlaybackBufferManager.Snapshot snapshot = playbackBufferManager == null
                ? PlaybackBufferManager.Snapshot.EMPTY : playbackBufferManager.snapshot();
        PlaybackResourceWarningPolicy.Type type = PlaybackResourceWarningPolicy.memoryType(
                playbackHasStarted,
                level,
                snapshot.heapUsedBytes,
                snapshot.heapMaxBytes,
                snapshot.allocatedBytes,
                snapshot.targetBytes
        );
        if (type == PlaybackResourceWarningPolicy.Type.NONE) return;
        if (showResourceWarning(type, snapshot, level)) {
            memoryWarningSamples = 2;
            memoryWarningCondition = type;
        }
    }

    private void showLoadingState(String message) {
        if (loadingPanel == null || isFinishing()) return;
        clearResourceWarning();
        loadingPanel.setVisibility(View.VISIBLE);
        String safeMessage = SafePlaybackText.detail(message == null ? "" : message.trim());
        if (!classicUi) {
            showModernLoadingStep(stripTrailingEllipsis(safeMessage));
            return;
        }
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
        clearResourceWarning();
        stopLoadingTextAnimation();
        if (loadingPanel != null) loadingPanel.setVisibility(View.GONE);
    }

    /**
     * Moderno: la onda anima la espera, así que el paso va sin puntos y entra con el
     * movimiento enfatizado de Material (sube y aparece). Mientras carga, el OSD se atenúa.
     */
    private void showModernLoadingStep(String step) {
        if (loadingProgress != null) loadingProgress.setVisibility(View.VISIBLE);
        if (step.equals(loadingMessageBase)) return;
        loadingMessageBase = step;
        loadingText.animate().cancel();
        loadingText.setText(step);
        loadingText.setAlpha(0f);
        loadingText.setTranslationY(dpToPx(5));
        loadingText.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400L)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
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
        closePlaybackSourceSelector();
        cancelQualityUpgrade();
        qualityRevertSource = null;
        tvvooNoSignalToken++;
        channelIndex = (requestedIndex % channels.size() + channels.size()) % channels.size();
        Channel channel = channels.get(channelIndex);
        tvvooBackupActive = PlaybackPreferences.channelIdentity(channel)
                .equals(pendingTvVooBackupIdentity) && TvVooBackup.has(channel);
        directBackupIndex = tvvooBackupActive ? pendingDirectBackupIndex : 0;
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

        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build());
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        playbackPreferences.rememberChannel(channel, channelIndex);

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

    private void prepareAndPlay() {
        if (player == null) return;
        player.prepare();
        player.play();
    }

    private void schedulePlaybackWatchdog() {
        if (playbackWatchdogScheduled || exiting || resourcesReleased || isFinishing()) return;
        playbackWatchdogScheduled = true;
        mainHandler.postDelayed(playbackWatchdog, PLAYBACK_WATCHDOG_INTERVAL_MS);
    }

    private void cancelPlaybackWatchdog() {
        mainHandler.removeCallbacks(playbackWatchdog);
        playbackWatchdogScheduled = false;
    }

    private boolean hasRenderedVideoFrame() {
        return playbackBitrateMeter != null
                && playbackBitrateMeter.snapshot().hasRenderedVideoFrame;
    }

    private void maybeSchedulePlaybackSourceStability() {
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

    private void cancelPlaybackSourceStability() {
        playbackSourceStabilityToken++;
        playbackSourceStabilityScheduledFor = "";
    }

    /**
     * Checks the playback boundary rather than network throughput. A stream
     * can keep downloading bytes while its decoder is no longer receiving
     * frames, so the watchdog also uses the last renderer timestamp.
     */
    private void checkPlaybackHealth() {
        if (player == null || playbackChannel == null || settingsOpen || loadFailed
                || playbackRecoveryFailed
                || playbackAutoRecoveryInFlight) return;

        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs < playbackRecoveryCooldownUntilElapsedRealtime) return;
        int state = player.getPlaybackState();
        boolean renderedFrame = hasRenderedVideoFrame();
        if (renderedFrame) {
            playbackHasStarted = true;
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
                    && PlaybackStallPolicy.shouldRecover(
                    isCncVersePlayback(), true,
                    playbackLoadingSinceElapsedRealtime,
                    nowMs,
                    lastMediaLoadAgeMs(measurements),
                    PLAYBACK_FREEZE_TIMEOUT_MS
            )) {
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
                && PlaybackStallPolicy.shouldRecover(isCncVersePlayback(), false,
                0L, Math.max(0L, (nowNs - lastFrameNs) / 1_000_000L),
                lastMediaLoadAgeMs(measurements), PLAYBACK_FREEZE_TIMEOUT_MS)) {
            requestFullPlaybackRecovery(classifyPlaybackStall(measurements));
        }
    }

    private String classifyPlaybackStall(PlaybackDiagnosticsWorker.Snapshot measurements) {
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

    private boolean isCncVersePlayback() {
        return currentPlaybackSource != null
                && "cncverse".equalsIgnoreCase(currentPlaybackSource.getResolverId());
    }

    private static long lastMediaLoadAgeMs(PlaybackDiagnosticsWorker.Snapshot measurements) {
        return measurements.lastMediaLoadRealtimeNs <= 0L ? -1L
                : Math.max(0L, (System.nanoTime() - measurements.lastMediaLoadRealtimeNs) / 1_000_000L);
    }

    /**
     * Renews one expired/damaged resolver source while retaining ExoPlayer.
     * The attempt is deliberately bounded; a second source failure falls
     * through to the existing decoder-level full recovery path.
     */
    private boolean requestPlaybackSourceRecovery(PlaybackException error) {
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

    private static final long TVVOO_CLEAN_LIFETIME_MS = 15L * 60_000L;

    private boolean isExpiredTvVooCleanSource() {
        ResolvedPlaybackSource source = currentPlaybackSource;
        if (source == null || !"tvvoo".equalsIgnoreCase(source.getResolverId())) return false;
        URI uri = source.getPlaybackUri();
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
        boolean noFreeze = host.endsWith("hayd.uk");
        return !noFreeze && SystemClock.elapsedRealtime() - currentSourceStartedElapsedRealtime
                >= TVVOO_CLEAN_LIFETIME_MS;
    }

    private static boolean isDecoderFailure(PlaybackException error) {
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
    private void requestFullPlaybackRecovery(String reason) {
        requestFullPlaybackRecovery(reason, false, false);
    }

    private void requestFullPlaybackRecovery(
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

    /** Canal con el que se resuelve la reproducción: el de respaldo si está activo. */
    private Channel resolutionChannelFor(Channel channel) {
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
    private boolean switchToTvVooBackup(String reason) {
        Channel channel = playbackChannel;
        if (channel == null || !TvVooBackup.has(channel)) return false;
        if (tvvooBackupActive) {
            if (TvVooBackup.hasTvVoo(channel)
                    || directBackupIndex + 1 >= TvVooBackup.directBackupsOf(channel).size()) {
                return false;
            }
            directBackupIndex++;
        } else {
            directBackupIndex = 0;
        }
        Log.i(PLAYBACK_HEALTH_TAG, "backup reason=" + reason + " index=" + directBackupIndex);
        pendingTvVooBackupIdentity = PlaybackPreferences.channelIdentity(channel);
        pendingDirectBackupIndex = directBackupIndex;
        tvvooBackupActive = true;
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
    private List<ResolvedPlaybackCandidate> withDirectPrincipal(
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

    private void performFullPlaybackRecovery(boolean renewSource) {
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
        if (tvvooBackupActive) {
            pendingTvVooBackupIdentity = identity;
            pendingDirectBackupIndex = directBackupIndex;
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

    private void releasePlayerForRecovery() {
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
        Channel resolutionChannel = resolutionChannelFor(channel);
        StreamResolver resolver = streamResolverRegistry.find(resolutionChannel);
        if (resolver == null) {
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

    private void handleResolutionFailure(
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

    private static final long QUALITY_UPGRADE_DELAY_MS = 15_000L;
    private static final long QUALITY_UPGRADE_VISIBLE_MS = 12_000L;
    private static final long QUALITY_REVERT_WINDOW_MS = 20_000L;
    private static final double QUALITY_UPGRADE_MIN_SPEED = 1.5d;

    private final Runnable qualityUpgradeCheck = this::startQualityUpgradeCheck;
    private final Runnable hideQualityUpgradeRunnable = this::hideQualityUpgrade;

    /** Una sola búsqueda por canal abierto, 15 s después de que el video arranca. */
    private void maybeScheduleQualityUpgradeCheck() {
        if (qualityUpgradeCheckedGeneration == playbackGeneration
                || currentPlaybackSource == null
                || !"tvvoo".equalsIgnoreCase(currentPlaybackSource.getResolverId())) return;
        qualityUpgradeCheckedGeneration = playbackGeneration;
        mainHandler.removeCallbacks(qualityUpgradeCheck);
        mainHandler.postDelayed(qualityUpgradeCheck, QUALITY_UPGRADE_DELAY_MS);
    }

    private void startQualityUpgradeCheck() {
        Channel channel = playbackChannel;
        if (channel == null || player == null || !player.isPlaying() || exiting
                || streamResolverRegistry == null || qualityUpgradeTask != null) return;
        Format video = player.getVideoFormat();
        int currentHeight = video == null ? 0 : Math.max(0, video.height);
        ResolvedPlaybackSource current = currentPlaybackSource;
        StreamResolver resolver = streamResolverRegistry.find(channel);
        if (currentHeight <= 0 || current == null || resolver == null
                || !"tvvoo".equalsIgnoreCase(resolver.getId())) return;
        long generation = playbackGeneration;
        ResolutionContext context = new ResolutionContext(30_000L);
        qualityUpgradeContext = context;
        qualityUpgradeTask = playbackExecutor.submit(() -> {
            try (ResolutionContext.Scope ignored = context.activate()) {
                List<ResolvedPlaybackCandidate> rows = resolver.resolvePlaybackCandidates(
                        channel, ResolutionProgressListener.NONE);
                ResolvedPlaybackCandidate best = null;
                for (ResolvedPlaybackCandidate row : rows) {
                    if (!row.isAvailable() || row.getSource() == null) continue;
                    if (row.getQualityHeight() <= currentHeight) continue;
                    if (row.getVariantId().equals(current.getVariantId())) continue;
                    if (best == null || row.getQualityHeight() > best.getQualityHeight()) best = row;
                }
                if (best == null) return;
                // Prueba exigente: dos segmentos completos, bien por sobre tiempo real.
                HlsStreamValidator.Sustained sustained = new HlsStreamValidator(
                        new TokenHttpClient(4_000, 10_000)).measureSustained(
                        best.getSource().getPlaybackUri(),
                        best.getSource().getRequestHeaders(),
                        context);
                if (sustained.speed < QUALITY_UPGRADE_MIN_SPEED) return;
                if (sustained.info != null && sustained.info.height <= currentHeight) return;
                ResolvedPlaybackCandidate offer = best;
                mainHandler.post(() -> showQualityUpgrade(channel, generation, offer, currentHeight));
            } catch (Exception ignored) {
                // Sin oferta: se sigue viendo la fuente actual sin avisar nada.
            } finally {
                mainHandler.post(() -> {
                    if (qualityUpgradeContext == context) {
                        qualityUpgradeContext = null;
                        qualityUpgradeTask = null;
                    }
                });
            }
        });
    }

    private void showQualityUpgrade(
            Channel channel,
            long generation,
            ResolvedPlaybackCandidate offer,
            int currentHeight
    ) {
        if (channel != playbackChannel || generation != playbackGeneration || exiting
                || isSourceSelectorVisible() || isGuideVisible() || settingsOpen
                || (exitDialog != null && exitDialog.isShowing())
                || (premiumSceneDialog != null && premiumSceneDialog.isShowing())) return;
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
    }

    private boolean isQualityUpgradeVisible() {
        return qualityUpgradeOverlay != null && qualityUpgradeOverlay.getVisibility() == View.VISIBLE;
    }

    private boolean handleQualityUpgradeKey(KeyEvent event) {
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

    private void hideQualityUpgrade() {
        mainHandler.removeCallbacks(hideQualityUpgradeRunnable);
        if (qualityUpgradeTimerAnimation != null) qualityUpgradeTimerAnimation.cancel();
        qualityUpgradeOffer = null;
        if (qualityUpgradeOverlay != null) qualityUpgradeOverlay.setVisibility(View.GONE);
    }

    private void cancelQualityUpgrade() {
        mainHandler.removeCallbacks(qualityUpgradeCheck);
        if (qualityUpgradeContext != null) qualityUpgradeContext.cancel();
        if (qualityUpgradeTask != null) qualityUpgradeTask.cancel(true);
        qualityUpgradeContext = null;
        qualityUpgradeTask = null;
        hideQualityUpgrade();
    }

    /** Cambia a la fuente mejor; si falla en los 20 s siguientes, vuelve sola a la anterior. */
    private void acceptQualityUpgrade() {
        ResolvedPlaybackSource offer = qualityUpgradeOffer;
        Channel channel = playbackChannel;
        hideQualityUpgrade();
        if (offer == null || channel == null || player == null
                || offer.isExpired(System.currentTimeMillis())) return;
        qualityRevertSource = currentPlaybackSource;
        qualityRevertUntilElapsedRealtime = SystemClock.elapsedRealtime() + QUALITY_REVERT_WINDOW_MS;
        switchToSource(channel, offer);
    }

    private boolean revertQualityUpgradeIfRecent() {
        ResolvedPlaybackSource previous = qualityRevertSource;
        Channel channel = playbackChannel;
        qualityRevertSource = null;
        if (previous == null || channel == null || player == null
                || SystemClock.elapsedRealtime() > qualityRevertUntilElapsedRealtime
                || previous.isExpired(System.currentTimeMillis())) return false;
        switchToSource(channel, previous);
        return true;
    }

    private void switchToSource(Channel channel, ResolvedPlaybackSource source) {
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
        startResolvedPlayback(channel, source, playbackGeneration, requestId);
    }

    // ---------------------------------------------------------------------------------
    // Reconexión: canal TvVoo sin señal y espera de red (2026-10-03)
    // ---------------------------------------------------------------------------------

    private static final long TVVOO_NO_SIGNAL_RETRY_MS = 60_000L;

    private void showTvVooNoSignal() {
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

    private void waitForNetworkThenRecover() {
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

    private void stopWaitingForNetwork() {
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

    private static boolean isPremiumRejection(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HighflyPremiumClient.RejectedException) return true;
        }
        return false;
    }

    /** Token Premium vencido o revocado: vincular de nuevo o ver la señal gratuita. */
    private void showPremiumRejected(Channel channel) {
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

    private void retryAfterPremiumChange() {
        if (playbackChannel == null) return;
        playbackRecoveryBudget.reset();
        playbackRecoveryFailed = false;
        playbackAutoRecoveryInFlight = false;
        requestFullPlaybackRecovery("Highfly Premium", true, true);
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
        // Change buffer profile only when crossing CNCVerse/non-CNCVerse. Normal channel
        // changes reuse the player; a recovery retains its profile and bounded allocator.
        boolean cncVerse = "cncverse".equalsIgnoreCase(source.getResolverId());
        if (playerUsesCncBuffering != cncVerse) {
            releasePlayerForRecovery();
            playerUsesCncBuffering = cncVerse;
            createPlayer();
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
        player.setMediaSource(mediaSource);
        showLoadingState(getString(R.string.loading_starting_playback));
        prepareAndPlay();
    }

    private void discardCurrentPlaybackSource() {
        cancelPlaybackSourceStability();
        currentPlaybackSource = null;
        if (playbackManifestCache != null) playbackManifestCache.clear();
        playbackManifestCache = null;
        activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    }

    private void resetPlaybackBitrateMeter() {
        playbackDiagnosticsActive = false;
        if (playbackBitrateMeter != null) playbackBitrateMeter.reset();
    }

    private MediaSource mediaSourceFor(Channel channel, ResolvedPlaybackSource source)
            throws java.io.IOException {
        String userAgent = AppStrings.isBlank(source.getUserAgent())
                ? PLAYER_USER_AGENT
                : source.getUserAgent();
        OkHttpClient playbackClient = MediaFlowPlaybackClient.forSource(source);
        OkHttpDataSource.Factory dataSourceFactory =
                new OkHttpDataSource.Factory(playbackClient).setUserAgent(userAgent);
        Map<String, String> headers = PlaybackRequestHeaders.withoutUserAgent(
                source.getRequestHeaders()
        );
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
        if ("highfly".equalsIgnoreCase(source.getResolverId())) {
            playbackDataSourceFactory = new HighflyPlaylistDataSource.Factory(
                    playbackDataSourceFactory
            );
        }
        MediaItem.Builder itemBuilder = mediaItemFor(channel, source.getPlaybackUri()).buildUpon();
        if ("cncverse".equalsIgnoreCase(source.getResolverId())) {
            // The bridge's small segments need runway, not low-latency playback.
            itemBuilder.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(PlaybackStallPolicy.CNC_LIVE_OFFSET_MS).build());
        }
        if (source.hasMimeType()) itemBuilder.setMimeType(source.getMimeType());
        return new DefaultMediaSourceFactory(playbackDataSourceFactory)
                .setLoadErrorHandlingPolicy(new PlaybackLoadErrorPolicy(source.isDynamicallyResolved()))
                .createMediaSource(itemBuilder
                        .setTag(Long.valueOf(startupMetrics.currentId())).build());
    }

    private void beginStartupMeasurement(Channel channel, PlaybackStartupMetrics.Reason reason) {
        StreamResolver resolver = channel == null || streamResolverRegistry == null
                ? null : streamResolverRegistry.find(channel);
        startupMetrics.begin(resolver == null ? "direct" : resolver.getId(), reason);
    }

    private void settlePlaybackEpisode(boolean playing) {
        if (!playbackRecoveryEpisode.onPlayingChanged(playing, System.nanoTime())) return;
        playbackRecoveryBudget.reset();
    }

    private boolean isCurrentPlayback(Channel channel, long expectedGeneration) {
        return !exiting
                && !isFinishing()
                && expectedGeneration == playbackGeneration
                && playbackChannel == channel;
    }

    private void showPlaybackFailure() {
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
        overlayAwaitingPlayback = true;
        showOverlay(true);
    }

    private void startPlaybackFromInput() {
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

    /** Stops playback when hidden but retains only unexpired in-session tokens. */
    private void stopPlaybackForFocusLoss() {
        boolean hasPlaybackSession = playbackChannel != null
                || currentPlaybackSource != null
                || (player != null && player.getCurrentMediaItem() != null);
        if (!hasPlaybackSession) return;
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
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }

    /** Reopens the previously selected channel with a fresh source/session. */
    private void restartCurrentPlaybackAfterFocusLoss() {
        if (resourcesReleased || settingsOpen || channels.isEmpty()) return;
        int safeIndex = Math.max(0, Math.min(channelIndex, channels.size() - 1));
        if (player == null) createPlayer();
        playChannel(safeIndex, false);
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

    /** Número del canal en el OSD; el moderno ya no muestra la categoría. El logo reemplaza al nombre. */
    private void bindOsdChannel(int displayNumber, Channel channel) {
        channelNumber.setText(String.format(Locale.ROOT, "%03d", displayNumber));
        channelName.setText(channel.getName());
    }

    private void updateProgrammeInfo() {
        if (channels.isEmpty() || channelIndex < 0 || channelIndex >= channels.size()) return;
        Channel channel = channels.get(channelIndex);
        long now = System.currentTimeMillis();
        EpgProgramme programme = epgData.findCurrent(channel.getTvgId(), now);

        if (programme == null) {
            contentTitle.setText(AppStrings.isBlank(channel.getGroup())
                    ? getString(R.string.live_content)
                    : channel.getGroup());
            programmeTime.setVisibility(View.GONE);
            if (osdDescription != null) osdDescription.setVisibility(View.GONE);
            if (osdNext != null) osdNext.setVisibility(View.GONE);
            liveProgress.setIndeterminate(true);
            updateProgrammeDetail();
            return;
        }

        contentTitle.setText(programme.getTitle());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String timeRange = timeFormat.format(new Date(programme.getStartMillis()))
                + (classicUi ? " — " : " – ")
                + timeFormat.format(new Date(programme.getStopMillis()));
        programmeTime.setText(classicUi ? timeRange : timeRange + "  ·  " + getString(
                R.string.guide_remaining, EpgGuideView.formatDuration(programme.getStopMillis() - now)));
        programmeTime.setVisibility(View.VISIBLE);

        String description = programme.getDescription();
        if (osdDescription != null) {
            // Moderno (0.5.60): la descripción solo va en el detalle, que se abre con OK.
            osdDescription.setText(description);
            boolean blank = AppStrings.isBlank(description);
            if (blank) osdDescriptionOpen = false;
            osdDescription.setVisibility(!blank && (classicUi || osdDescriptionOpen)
                    ? View.VISIBLE : View.GONE);
        }

        long duration = programme.getStopMillis() - programme.getStartMillis();
        int progress = duration <= 0 ? 0 : (int) Math.max(0, Math.min(1000,
                ((now - programme.getStartMillis()) * 1000L) / duration));
        liveProgress.setIndeterminate(false);
        liveProgress.setMax(1000);
        liveProgress.setProgress(progress);

        if (osdNext != null) {
            if (osdDescriptionOpen) osdNext.setVisibility(View.GONE);
            else bindOsdNext(channel, programme, timeFormat);
        }
        updateProgrammeDetail();
    }

    /** «Después · 22:15 Programa», con la campana cyan si tiene recordatorio. */
    private void bindOsdNext(Channel channel, EpgProgramme current, SimpleDateFormat timeFormat) {
        EpgProgramme next = null;
        for (EpgProgramme candidate : epgData.findUpcoming(channel.getTvgId(),
                current.getStopMillis(), 2)) {
            if (candidate.getStartMillis() >= current.getStopMillis()) {
                next = candidate;
                break;
            }
        }
        if (next == null) {
            osdNext.setVisibility(View.GONE);
            return;
        }
        String prefix = getString(R.string.guide_next,
                timeFormat.format(new Date(next.getStartMillis()))) + "  ";
        android.text.SpannableString text = new android.text.SpannableString(prefix + next.getTitle());
        text.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.white)),
                prefix.length(), text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        osdNext.setText(text);
        refreshReminderIds();
        boolean reminded = reminderIds.contains(
                PlaybackPreferences.channelIdentity(channel) + "@" + next.getStartMillis());
        android.graphics.drawable.Drawable bell = null;
        if (reminded) {
            bell = getDrawable(R.drawable.ic_reminder_bell);
            if (bell != null) bell.setBounds(0, 0, dpToPx(10), dpToPx(10));
        }
        osdNext.setCompoundDrawablesRelative(bell, null, null, null);
        osdNext.setVisibility(View.VISIBLE);
    }

    private void updateProgrammeDetail() {
        if (programmeDetailOverlay == null
                || programmeDetailOverlay.getVisibility() != View.VISIBLE
                || channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return;

        Channel channel = channels.get(channelIndex);
        long now = System.currentTimeMillis();
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        List<EpgProgramme> upcoming = epgData.findUpcoming(channel.getTvgId(), now, 3);
        EpgProgramme first = upcoming.isEmpty() ? null : upcoming.get(0);
        EpgProgramme current = first != null && first.getStartMillis() <= now ? first : null;

        if (first == null) {
            detailLive.setVisibility(classicUi ? View.VISIBLE : View.GONE); // Moderno: sin «EN VIVO».
            detailLabel.setText(AppStrings.isBlank(channel.getGroup())
                    ? getString(R.string.live_content) : channel.getGroup());
            detailTitle.setText(R.string.epg_no_information);
            detailMeta.setText(channel.getName());
            detailProgress.setVisibility(View.GONE);
            detailDescription.setVisibility(View.GONE);
            detailSide.setVisibility(View.GONE);
            return;
        }

        detailLive.setVisibility(classicUi && current != null ? View.VISIBLE : View.GONE);
        detailLabel.setText(current != null
                ? getString(R.string.light_epg_now)
                : getString(R.string.light_epg_next_at,
                        timeFormat.format(new Date(first.getStartMillis()))));
        detailTitle.setText(first.getTitle());
        String range = formatProgrammeRange(first, timeFormat);
        String duration = formatEpgDuration(first.getStopMillis() - first.getStartMillis());
        detailMeta.setText(current != null
                ? getString(R.string.detail_meta_live, range, duration,
                        formatEpgDuration(current.getStopMillis() - now))
                : getString(R.string.detail_meta, range, duration));
        if (current != null) {
            long total = current.getStopMillis() - current.getStartMillis();
            int progress = total <= 0 ? 0 : (int) Math.max(0, Math.min(1000,
                    ((now - current.getStartMillis()) * 1000L) / total));
            detailProgress.setMax(1000);
            detailProgress.setProgress(progress);
            detailProgress.setVisibility(View.VISIBLE);
        } else {
            detailProgress.setVisibility(View.GONE);
        }
        String description = first.getDescription();
        detailDescription.setText(description);
        detailDescription.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);

        EpgProgramme next = upcoming.size() > 1 ? upcoming.get(1) : null;
        EpgProgramme after = upcoming.size() > 2 ? upcoming.get(2) : null;
        // Moderno (0.5.60): el detalle no muestra los próximos programas.
        detailSide.setVisibility(!classicUi || next == null ? View.GONE : View.VISIBLE);
        if (next != null) {
            detailNextLabel.setText(getString(R.string.light_epg_next_at,
                    timeFormat.format(new Date(next.getStartMillis()))));
            detailNextTitle.setText(next.getTitle());
        }
        detailAfterLabel.setVisibility(after == null ? View.GONE : View.VISIBLE);
        detailAfterTitle.setVisibility(after == null ? View.GONE : View.VISIBLE);
        if (after != null) {
            detailAfterLabel.setText(getString(R.string.light_epg_after_at,
                    timeFormat.format(new Date(after.getStartMillis()))));
            detailAfterTitle.setText(after.getTitle());
        }
    }

    private String formatEpgDuration(long millis) {
        long totalMinutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        int hours = (int) (totalMinutes / 60L);
        int minutes = (int) (totalMinutes % 60L);
        if (hours == 0) return getString(R.string.light_epg_minutes, minutes);
        if (minutes == 0) return getString(R.string.light_epg_hours, hours);
        return getString(R.string.light_epg_hours_minutes, hours, minutes);
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
        // Sin logo: moderno muestra el nombre; clásico, las iniciales en el recuadro.
        String fallback = classicUi ? initials(channel.getName()) : channel.getName();
        String expectedIdentity = PlaybackPreferences.channelIdentity(channel);
        long requestGeneration = ++logoRequestGeneration;
        if (!expectedIdentity.equals(displayedLogoIdentity)) {
            finishLogoSizeAdjust(false);
            displayedLogoBitmap = null;
            displayedLogoKey = null;
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
        // Se carga con margen de sobra: el tamaño final lo fija el tamaño óptico.
        int targetWidthPx = classicUi ? dpToPx(78) : dpToPx(LogoFit.OSD_LOGO_MAX_WIDTH_DP * 1.5f);
        int targetHeightPx = classicUi ? dpToPx(54) : dpToPx(LogoFit.OSD_LOGO_MAX_HEIGHT_DP * 2f);
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
                android.graphics.Bitmap trimmedCached = classicUi ? cached : LogoFit.trim(cached, logoUri);
                mainHandler.post(() -> showChannelLogo(
                        trimmedCached,
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
                android.graphics.Bitmap trimmedRefreshed = classicUi
                        ? refreshed.getBitmap() : LogoFit.trim(refreshed.getBitmap(), logoUri);
                mainHandler.post(() -> showChannelLogo(
                        trimmedRefreshed,
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
        if (classicUi) {
            // Clásico: logo contenido en su recuadro fijo de 78×54 dp.
            channelLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            channelLogo.setImageBitmap(bitmap);
            channelLogo.setVisibility(View.VISIBLE);
            channelLogoFallback.setVisibility(View.GONE);
            displayedLogoIdentity = expectedIdentity;
            return;
        }
        sizeModernLogo(bitmap);
        channelLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        channelLogo.setImageBitmap(bitmap);
        channelLogo.setVisibility(View.VISIBLE);
        channelLogoFallback.setVisibility(View.GONE);
        displayedLogoIdentity = expectedIdentity;
        displayedLogoBitmap = bitmap;
        Channel channel = expectedIndex >= 0 && expectedIndex < channels.size()
                ? channels.get(expectedIndex) : null;
        displayedLogoKey = channel == null ? null : LogoFit.logoKey(channel.getLogoUri());
    }

    /**
     * Tamaño óptico: la misma superficie visual para todos los logos (los UHD, igual de altos
     * que su versión normal), con la corrección por tinta y el ajuste a mano de ese logo.
     */
    private void sizeModernLogo(android.graphics.Bitmap bitmap) {
        float[] size = LogoFit.opticalSize(bitmap,
                dpToPx(LogoFit.OSD_LOGO_AREA_WIDTH_DP) * (float) dpToPx(LogoFit.OSD_LOGO_AREA_HEIGHT_DP),
                dpToPx(LogoFit.OSD_LOGO_MAX_WIDTH_DP), dpToPx(LogoFit.OSD_LOGO_MAX_HEIGHT_DP));
        ViewGroup.LayoutParams logoParams = channelLogo.getLayoutParams();
        logoParams.width = Math.max(1, Math.round(size[0]));
        logoParams.height = Math.max(1, Math.round(size[1]));
        channelLogo.setLayoutParams(logoParams);
    }

    // ---------------------------------------------------------------------------------
    // Tamaño del logo en vivo (0.5.60): Opciones › En reproducción › Tamaño del logo
    // ---------------------------------------------------------------------------------

    private static final float LOGO_SIZE_STEP = 0.05f;

    private void startLogoSizeAdjust() {
        if (classicUi || logoSizeAdjust == null || channelLogoFrame == null
                || displayedLogoBitmap == null || displayedLogoKey == null || logoSizeAdjusting) return;
        logoSizeAdjusting = true;
        logoSizeKey = displayedLogoKey;
        logoSizeOriginal = LogoFit.userScale(logoSizeKey);
        logoSizeScale = logoSizeOriginal;
        showOverlay(true);
        int padding = dpToPx(8);
        channelLogoFrame.setBackgroundResource(R.drawable.logo_size_frame);
        channelLogoFrame.setPadding(padding, padding, padding, padding);
        logoSizeAdjust.setVisibility(View.VISIBLE);
        applyLogoSize();
    }

    private void applyLogoSize() {
        LogoFit.setUserScale(logoSizeKey, logoSizeScale);
        if (displayedLogoBitmap != null) sizeModernLogo(displayedLogoBitmap);
        logoSizeValue.setText(getString(R.string.logo_size_value, Math.round(logoSizeScale * 100f)));
        positionLogoSizeAdjust();
    }

    /** El control va a la derecha del logo, centrado en su alto. */
    private void positionLogoSizeAdjust() {
        if (logoSizeAdjust == null || channelLogoFrame == null
                || !(logoSizeAdjust.getParent() instanceof View)) return;
        int[] frame = new int[2];
        int[] parent = new int[2];
        channelLogoFrame.getLocationInWindow(frame);
        ((View) logoSizeAdjust.getParent()).getLocationInWindow(parent);
        float x = frame[0] - parent[0] + channelLogoFrame.getWidth() + dpToPx(20);
        float y = frame[1] - parent[1]
                + (channelLogoFrame.getHeight() - logoSizeAdjust.getHeight()) / 2f;
        if (logoSizeAdjust.getX() != x) logoSizeAdjust.setX(x);
        if (logoSizeAdjust.getY() != y) logoSizeAdjust.setY(y);
    }

    /** ◀ ▶ cambian de 5 en 5 %, ▼ vuelve al automático, OK guarda. Otra tecla cancela. */
    private boolean handleLogoSizeKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean ours = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (!ours) {
            // Atrás, cambio de canal u otra tecla: se deshace el ajuste y la tecla sigue su curso.
            if (event.getAction() == KeyEvent.ACTION_DOWN) finishLogoSizeAdjust(false);
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                stepLogoSize(-LOGO_SIZE_STEP);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                stepLogoSize(LOGO_SIZE_STEP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                logoSizeScale = 1f;
                applyLogoSize();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) finishLogoSizeAdjust(true);
                return true;
            default:
                return true;
        }
    }

    private void stepLogoSize(float delta) {
        float next = Math.round((logoSizeScale + delta) * 20f) / 20f;
        logoSizeScale = Math.max(LogoFit.USER_SCALE_MIN, Math.min(LogoFit.USER_SCALE_MAX, next));
        applyLogoSize();
    }

    private void finishLogoSizeAdjust(boolean save) {
        if (!logoSizeAdjusting) return;
        logoSizeAdjusting = false;
        if (save) LogoScales.save(this, logoSizeKey, logoSizeScale);
        else LogoFit.setUserScale(logoSizeKey, logoSizeOriginal);
        logoSizeAdjust.setVisibility(View.GONE);
        channelLogoFrame.setBackground(null);
        channelLogoFrame.setPadding(0, 0, 0, 0);
        if (displayedLogoBitmap != null) sizeModernLogo(displayedLogoBitmap);
        if (guideView != null) guideView.invalidate();
        mainHandler.removeCallbacks(hideOverlay);
        mainHandler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS);
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

    private int dpToPx(float dp) {
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
            if (cue.text != null && !AppStrings.isBlank(cue.text.toString())) {
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
        hideProgrammeDetail.run();
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        channelOverlay.setVisibility(View.VISIBLE);
        // Moderno: la hora y la fecha van dentro del OSD; el reloj aparte es del clásico.
        clock.setVisibility(classicUi ? View.VISIBLE : View.GONE);
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

    private boolean isProgrammeDetailVisible() {
        return osdDescriptionOpen || (programmeDetailOverlay != null
                && programmeDetailOverlay.getVisibility() == View.VISIBLE);
    }

    private boolean isGuideVisible() {
        return guideView != null && guideView.getVisibility() == View.VISIBLE;
    }

    /** Guía completa: filas de canales y bloques de programas; OK abre el canal enfocado. */
    private void openGuide() {
        if (channels.isEmpty() || guideView == null || settingsOpen || exiting) return;
        closePlaybackSourceSelector();
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        hideProgrammeDetail.run();
        mainHandler.removeCallbacks(hideOverlay);
        overlayAwaitingPlayback = false;
        hideOverlay.run();
        long now = System.currentTimeMillis();
        rebuildGuideFilters();
        guideFiltersFocused = false;
        guideNavigator = new EpgGuideNavigator(guideRows.size(), guidePlayingRow(), now);
        refreshReminderIds();
        guideOkDown = false;
        guideOkLongHandled = false;
        guideSurface.bind(guideSource, guideNavigator, now);
        guideView.setVisibility(View.VISIBLE);
    }

    /** «Todos» más las categorías (grupos) de la lista, en el orden en que aparecen. */
    private void rebuildGuideFilters() {
        String selected = guideFilterIndex > 0 && guideFilterIndex < guideFilters.size()
                ? guideFilters.get(guideFilterIndex) : null;
        guideFilters.clear();
        guideFilters.add(getString(R.string.guide_filter_all));
        Set<String> seen = new HashSet<>();
        for (Channel channel : channels) {
            String group = channel.getGroup();
            if (!AppStrings.isBlank(group) && seen.add(group.trim())) guideFilters.add(group.trim());
        }
        guideFilterIndex = selected == null ? 0 : Math.max(0, guideFilters.indexOf(selected));
        rebuildGuideRows();
    }

    private void rebuildGuideRows() {
        guideRows.clear();
        String filter = guideFilterIndex > 0 && guideFilterIndex < guideFilters.size()
                ? guideFilters.get(guideFilterIndex) : null;
        for (int index = 0; index < channels.size(); index++) {
            String group = channels.get(index).getGroup();
            if (filter == null || (group != null && filter.equals(group.trim()))) guideRows.add(index);
        }
    }

    /** Fila del canal en reproducción dentro del filtro actual, o la primera. */
    private int guidePlayingRow() {
        return Math.max(0, guideRows.indexOf(channelIndex));
    }

    /** Índice en {@link #channels} de una fila de la guía, o -1. */
    private int guideChannelIndex(int row) {
        return row < 0 || row >= guideRows.size() ? -1 : guideRows.get(row);
    }

    private Channel guideChannel(int row) {
        int index = guideChannelIndex(row);
        return index < 0 || index >= channels.size() ? null : channels.get(index);
    }

    private void changeGuideFilter(int delta) {
        int next = Math.max(0, Math.min(guideFilters.size() - 1, guideFilterIndex + delta));
        if (next == guideFilterIndex) return;
        guideFilterIndex = next;
        rebuildGuideRows();
        long now = System.currentTimeMillis();
        guideNavigator = new EpgGuideNavigator(guideRows.size(), guidePlayingRow(), now);
        guideSurface.bind(guideSource, guideNavigator, now);
    }

    /** Logo de la guía: si no está en memoria se carga en segundo plano y se redibuja. */
    private android.graphics.Bitmap guideLogo(Channel channel) {
        URI logoUri = channel.getLogoUri();
        if (logoUri == null || !("http".equalsIgnoreCase(logoUri.getScheme())
                || "https".equalsIgnoreCase(logoUri.getScheme()))) return null;
        String key = logoUri.toString();
        android.graphics.Bitmap bitmap = guideLogos.get(key);
        if (bitmap != null || !guideLogoRequests.add(key)) return bitmap;
        int width = dpToPx(90 * LogoFit.LOGO_SCALE);
        int height = dpToPx(36 * LogoFit.LOGO_SCALE);
        logoCacheExecutor.submit(() -> {
            android.graphics.Bitmap loaded = channelLogoCache.loadCached(logoUri, width, height);
            if (loaded == null) {
                try {
                    loaded = channelLogoCache.load(logoUri, width, height);
                } catch (Exception ignored) {
                    // Sin logo: la guía muestra el nombre del canal.
                }
            }
            android.graphics.Bitmap result = LogoFit.trim(loaded, logoUri);
            mainHandler.post(() -> {
                if (result == null) {
                    guideLogoRequests.remove(key);
                    return;
                }
                guideLogos.put(key, result);
                if (isGuideVisible()) guideView.invalidate();
            });
        });
        return null;
    }

    private void closeGuide() {
        if (guideView != null) guideView.setVisibility(View.GONE);
    }

    private final EpgGuideView.Source guideSource = new EpgGuideView.Source() {
        @Override public int rowCount() { return guideRows.size(); }

        @Override public String number(int row) {
            Channel channel = guideChannel(row);
            if (channel == null) return "";
            int number = publishedPlaybackCatalog.numberFor(channel, guideChannelIndex(row) + 1);
            return String.format(Locale.ROOT, "%03d", number);
        }

        @Override public String name(int row) {
            Channel channel = guideChannel(row);
            return channel == null ? "" : channel.getName();
        }

        @Override public String category(int row) {
            Channel channel = guideChannel(row);
            return channel == null || AppStrings.isBlank(channel.getGroup()) ? "" : channel.getGroup().trim();
        }

        @Override public android.graphics.Bitmap logo(int row) {
            Channel channel = guideChannel(row);
            return channel == null ? null : guideLogo(channel);
        }

        @Override public List<EpgProgramme> programmes(int row, long fromMillis, long toMillis) {
            Channel channel = guideChannel(row);
            if (channel == null) return java.util.Collections.emptyList();
            return epgData.findInWindow(channel.getTvgId(), fromMillis, toMillis);
        }

        @Override public int playingRow() { return guideRows.indexOf(channelIndex); }

        @Override public boolean hasReminder(int row, EpgProgramme programme) {
            Channel channel = guideChannel(row);
            if (channel == null || reminderIds.isEmpty()) return false;
            return reminderIds.contains(PlaybackPreferences.channelIdentity(channel)
                    + "@" + programme.getStartMillis());
        }

        @Override public List<String> filters() { return guideFilters; }

        @Override public int selectedFilter() { return guideFilterIndex; }

        @Override public boolean filtersFocused() { return guideFiltersFocused; }
    };

    /** Programas de la fila enfocada en un rango amplio para saltar entre bloques. */
    private List<EpgProgramme> guideRowProgrammes() {
        Channel channel = guideChannel(guideNavigator.getRow());
        if (channel == null) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        return epgData.findInWindow(channel.getTvgId(),
                now - EpgGuideNavigator.WINDOW_MILLIS,
                now + EpgGuideNavigator.MAX_AHEAD_MILLIS + EpgGuideNavigator.WINDOW_MILLIS);
    }

    private boolean handleGuideKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_GUIDE) {
            if (down && event.getRepeatCount() == 0) closeGuide();
            return true;
        }
        if (guideFiltersFocused) return handleGuideFilterKey(keyCode, down);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!down) return true;
                if (guideNavigator.moveRow(-1)) {
                    guideSurface.refresh(System.currentTimeMillis());
                } else if (event.getRepeatCount() == 0 && guideFilters.size() > 1 && !classicUi) {
                    // Arriba desde el primer canal: foco a los filtros de categoría.
                    guideFiltersFocused = true;
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down && guideNavigator.moveRow(-1)) guideSurface.refresh(System.currentTimeMillis());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down && guideNavigator.moveRow(1)) guideSurface.refresh(System.currentTimeMillis());
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && guideNavigator.moveTime(-1, guideRowProgrammes())) {
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && guideNavigator.moveTime(1, guideRowProgrammes())) {
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // OK corto (al soltar) abre el canal; OK mantenido programa o quita
                // el recordatorio del programa enfocado.
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        guideOkDown = true;
                        guideOkLongHandled = false;
                    } else if (guideOkDown && !guideOkLongHandled) {
                        guideOkLongHandled = true;
                        toggleGuideReminder();
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    boolean shortPress = guideOkDown && !guideOkLongHandled;
                    guideOkDown = false;
                    guideOkLongHandled = false;
                    if (shortPress) {
                        int row = guideChannelIndex(guideNavigator.getRow());
                        if (row < 0) return true;
                        closeGuide();
                        if (row != channelIndex) {
                            playChannel(row);
                        } else {
                            showOverlay(false);
                        }
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                return true;
            default:
                return false;
        }
    }

    /** Teclas con el foco en los filtros: ◀ ▶ cambian la categoría; ▼ u OK vuelven a los canales. */
    private boolean handleGuideFilterKey(int keyCode, boolean down) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) changeGuideFilter(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) changeGuideFilter(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down) {
                    guideFiltersFocused = false;
                    guideOkDown = false;
                    guideOkLongHandled = false;
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                return true;
            default:
                return false;
        }
    }

    /** Si falló el enlace directo Highfly del runner, el próximo intento usa el resolutor. */
    private void markPublishedHighflyLinkFailed() {
        if (currentPlaybackSource != null
                && "highfly".equalsIgnoreCase(currentPlaybackSource.getResolverId())) {
            PublishedHighflyLinks.markFailed(currentPlaybackSource.getPlaybackUri());
        }
    }

    private void refreshReminderIds() {
        reminderIds.clear();
        for (ProgramReminder reminder : ReminderAlerts.list(this)) reminderIds.add(reminder.id());
    }

    /** Programa o quita el recordatorio del programa enfocado en la Guía. */
    private void toggleGuideReminder() {
        Channel channel = guideChannel(guideNavigator.getRow());
        if (channel == null) return;
        EpgProgramme programme = guideNavigator.focusedProgramme(guideRowProgrammes());
        if (programme == null) {
            Toast.makeText(this, R.string.reminder_no_programme, Toast.LENGTH_SHORT).show();
            return;
        }
        ProgramReminder reminder = new ProgramReminder(
                PlaybackPreferences.channelIdentity(channel),
                channel.getName(),
                programme.getTitle(),
                programme.getStartMillis(),
                programme.getStopMillis()
        );
        boolean alreadySet = reminderIds.contains(reminder.id());
        if (!alreadySet && programme.getStartMillis() <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.reminder_already_started, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean added;
        try {
            added = ReminderAlerts.toggle(this, reminder);
        } catch (RuntimeException error) {
            Log.w(PLAYBACK_HEALTH_TAG, "no se pudo programar el recordatorio", error);
            return;
        }
        refreshReminderIds();
        guideSurface.refresh(System.currentTimeMillis());
        Toast.makeText(this, added
                ? getString(R.string.reminder_added, programme.getTitle(),
                        ReminderAlerts.when(programme.getStartMillis()))
                : getString(R.string.reminder_removed), Toast.LENGTH_SHORT).show();
    }

    private void showProgrammeDetail() {
        if (channels.isEmpty() || channelIndex < 0 || channelIndex >= channels.size()) return;
        if (!classicUi) {
            showOsdDescription();
            return;
        }
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        // El detalle se apoya sobre el OSD: ambos quedan visibles y se cierran juntos.
        showOverlay(true);
        // El detalle toma el lugar del bloque del OSD; los datos técnicos siguen visibles.
        if (osdHero != null) osdHero.setVisibility(View.INVISIBLE);
        programmeDetailOverlay.setVisibility(View.VISIBLE);
        updateProgrammeDetail();
        mainHandler.postDelayed(hideProgrammeDetailWithOverlay, PROGRAMME_DETAIL_TIMEOUT_MS);
    }

    /** Moderno: OK abre la descripción en el mismo OSD; sin descripción no pasa nada. */
    private void showOsdDescription() {
        if (osdDescription == null) return;
        EpgProgramme programme = epgData.findCurrent(
                channels.get(channelIndex).getTvgId(), System.currentTimeMillis());
        if (programme == null || AppStrings.isBlank(programme.getDescription())) return;
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        showOverlay(true);
        osdDescriptionOpen = true;
        updateProgrammeInfo();
        mainHandler.postDelayed(hideProgrammeDetailWithOverlay, PROGRAMME_DETAIL_TIMEOUT_MS);
    }

    private boolean isSourceSelectorVisible() {
        return sourceSelectorOverlay != null
                && sourceSelectorOverlay.getVisibility() == View.VISIBLE;
    }

    /** Opens the shared source/quality chooser without disturbing the current player. */
    private void openPlaybackSourceSelector() {
        if (exiting || resourcesReleased || settingsOpen || player == null
                || !hasWindowFocus() || playbackChannel == null
                || playbackResolutionTask != null
                || streamResolverRegistry == null) return;
        Channel versionsChannel = TvVooBackup.hasTvVoo(playbackChannel)
                ? TvVooBackup.resolutionChannel(playbackChannel) : playbackChannel;
        if (versionsChannel == null) versionsChannel = playbackChannel;
        StreamResolver resolver = streamResolverRegistry.find(versionsChannel);
        if (isSourceSelectorVisible() || sourceCandidateTask != null) return;

        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        hideProgrammeDetail.run();
        sourceSelectorTitle.setText(getString(R.string.source_selector_title));
        sourceSelectorChannel.setText(playbackChannel.getName());
        sourceSelectorOverlay.setVisibility(View.VISIBLE);
        sourceSelectorFixedHint = !classicUi && resolver instanceof TvVooStreamResolver;
        if (resolver != null && supportsSourceSelector(resolver)) {
            startSourceSelectorQuery(playbackChannel, versionsChannel, resolver);
            if (sourceSelectorFixedHint) {
                renderSourcePlaceholders(((TvVooStreamResolver) resolver).plannedVersions(versionsChannel));
            }
        } else {
            renderDirectQualitySelector(playbackChannel);
        }
    }

    /** TvVoo moderno: el subtítulo no cambia y las filas no se mueven mientras carga. */
    private boolean sourceSelectorFixedHint;
    private static final int SOURCE_ROW_HEIGHT_DP = 42;
    private static final int SOURCE_ROW_GAP_DP = 4;
    private static final int SOURCE_ROWS_VISIBLE = 5;

    /** Una fila por versión conocida, con «Probando…», en el mismo lugar que tendrá al final. */
    private void renderSourcePlaceholders(List<TvVooStreamResolver.PlannedVersion> versions) {
        if (versions == null || versions.isEmpty() || !isSourceSelectorVisible()) return;
        sourceSelectorOptions.removeAllViews();
        sourceCandidateViews.clear();
        sourceSelectorStatus.setText(R.string.source_selector_versions_hint);
        for (int index = 0; index < versions.size(); index++) {
            TvVooStreamResolver.PlannedVersion version = versions.get(index);
            View row = sceneRow(version.name, getString(R.string.source_selector_consulting_row), "—",
                    getString(R.string.source_selector_testing), false, true, version.preferred);
            sourceSelectorOptions.addView(row, sourceRowParams(index));
            sourceCandidateViews.add(row);
        }
        fitSourceSelectorList(versions.size());
        sourceCandidateFocusIndex = 0;
        sourceCandidateViews.get(0).requestFocus();
    }

    private LinearLayout.LayoutParams sourceRowParams(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(SOURCE_ROW_HEIGHT_DP));
        params.setMargins(0, index == 0 ? 0 : dp(SOURCE_ROW_GAP_DP), 0, 0);
        return params;
    }

    /** La lista mide lo que ocupan sus filas (hasta 5 visibles; más, se desplaza). */
    private void fitSourceSelectorList(int rows) {
        View scroll = findViewById(R.id.source_selector_scroll);
        if (scroll == null || classicUi) return;
        int visible = Math.max(1, Math.min(rows, SOURCE_ROWS_VISIBLE));
        ViewGroup.LayoutParams params = scroll.getLayoutParams();
        params.height = dp(visible * SOURCE_ROW_HEIGHT_DP + (visible - 1) * SOURCE_ROW_GAP_DP);
        scroll.setLayoutParams(params);
    }

    private static boolean supportsSourceSelector(StreamResolver resolver) {
        if (resolver == null || AppStrings.isBlank(resolver.getId())) return false;
        String id = resolver.getId();
        return "tvvoo".equalsIgnoreCase(id) || "highfly".equalsIgnoreCase(id);
    }

    private void startSourceSelectorQuery(Channel channel, StreamResolver resolver) {
        startSourceSelectorQuery(channel, channel, resolver);
    }

    /**
     * Consulta las versiones de {@code resolutionChannel} para el canal en reproducción
     * {@code channel} (distintos solo cuando un canal directo tiene respaldo TvVoo).
     */
    private void startSourceSelectorQuery(
            Channel channel,
            Channel resolutionChannel,
            StreamResolver resolver
    ) {
        if (channel == null || resolver == null || !isSourceSelectorVisible()) return;
        if (sourceCandidateContext != null) sourceCandidateContext.cancel();
        if (sourceCandidateTask != null) sourceCandidateTask.cancel(true);
        sourceCandidateTask = null;
        sourceCandidateContext = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        sourceCandidateViews.clear();
        sourceCandidateFocusIndex = -1;
        sourceSelectorCloseFocused = false;
        sourceSelectorOptions.removeAllViews();
        sourceSelectorStatus.setText(getString(sourceSelectorFixedHint
                ? R.string.source_selector_versions_hint : R.string.source_selector_consulting));

        long requestId = ++sourceCandidateRequestId;
        long expectedGeneration = playbackGeneration;
        ResolutionContext context = new ResolutionContext(20_000L);
        sourceCandidateContext = context;
        ResolutionProgressListener listener = progress -> mainHandler.post(() -> {
            if (requestId != sourceCandidateRequestId
                    || !isSourceSelectorVisible()
                    || !isCurrentPlayback(channel, expectedGeneration)) return;
            if (!sourceSelectorFixedHint) {
                sourceSelectorStatus.setText(sourceSelectorProgressText(progress));
            }
        });
        sourceCandidateTask = playbackExecutor.submit(() -> {
            try (ResolutionContext.Scope ignored = context.activate()) {
                context.check();
                List<ResolvedPlaybackCandidate> resolved = withDirectPrincipal(
                        channel,
                        resolutionChannel,
                        resolver.resolvePlaybackCandidates(resolutionChannel, listener)
                );
                context.check();
                mainHandler.post(() -> finishSourceSelectorQuery(
                        requestId,
                        channel,
                        expectedGeneration,
                        resolved
                ));
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) return;
                mainHandler.post(() -> failSourceSelectorQuery(
                        requestId,
                        channel,
                        expectedGeneration
                ));
            }
        });
    }

    private void finishSourceSelectorQuery(
            long requestId,
            Channel channel,
            long expectedGeneration,
            List<ResolvedPlaybackCandidate> resolved
    ) {
        if (requestId != sourceCandidateRequestId
                || !isSourceSelectorVisible()
                || !isCurrentPlayback(channel, expectedGeneration)) return;
        sourceCandidateTask = null;
        sourceCandidateContext = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        if (resolved != null) sourceCandidates.addAll(resolved);
        if (sourceCandidates.isEmpty()) {
            // Media3 may already expose adaptive variants even when the
            // provider's alternate-source endpoint is empty. Keep the same
            // window useful instead of showing a dead end.
            renderDirectQualitySelector(channel);
            return;
        }
        renderSourceSelectorOptions();
    }

    private void failSourceSelectorQuery(
            long requestId,
            Channel channel,
            long expectedGeneration
    ) {
        if (requestId != sourceCandidateRequestId
                || !isSourceSelectorVisible()
                || !isCurrentPlayback(channel, expectedGeneration)) return;
        sourceCandidateTask = null;
        sourceCandidateContext = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        sourceCandidateViews.clear();
        sourceCandidateFocusIndex = -1;
        sourceSelectorCloseFocused = false;
        sourceSelectorOptions.removeAllViews();
        renderDirectQualitySelector(channel);
    }

    private String sourceSelectorProgressText(ResolutionProgress progress) {
        if (progress == null || progress.getStage() == null) {
            return getString(R.string.source_selector_consulting);
        }
        int current = progress.getCurrent();
        int total = progress.getTotal();
        return switch (progress.getStage()) {
            case ALIAS_ATTEMPT -> current > 0 && total > 1
                    ? "Consultando aliases " + current + "/" + total + "…"
                    : "Consultando aliases…";
            case CATALOG_REQUEST, CATALOG_PAGE -> "Consultando catálogo TvVoo…";
            case CATALOG_PARSED -> "Procesando fuentes publicadas…";
            case SOURCE_REQUEST -> current > 0 && total > 1
                    ? "Solicitando fuentes " + current + "/" + total + "…"
                    : "Solicitando fuentes…";
            case SOURCE_CANDIDATE -> current > 0 && total > 1
                    ? "Validando fuentes " + current + "/" + total + "…"
                    : getString(R.string.source_selector_validating);
            case HLS_PLAYLIST -> "Validando playlist HLS…";
            case HLS_VARIANT -> "Validando variante HLS…";
            case HLS_SEGMENT -> "Validando segmento HLS…";
            case SOURCE_FOUND -> getString(R.string.source_selector_ready);
            default -> getString(R.string.source_selector_consulting);
        };
    }

    private void renderSourceSelectorOptions() {
        playbackOptions.clear();
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            playbackOptions.add(PlaybackOption.source(
                    candidate,
                    isCurrentSource(candidate.getSource())
            ));
        }
        int available = 0;
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            if (candidate.isAvailable()) available++;
        }
        renderPlaybackOptions(sourceSelectorFixedHint
                ? getString(R.string.source_selector_versions_hint)
                : available == sourceCandidates.size()
                ? getString(R.string.source_selector_ready_count, available)
                : getString(R.string.source_selector_versions_count, available,
                        sourceCandidates.size()));
    }

    /** Fila «escena» del selector: nombre (+ «PRINCIPAL»), detalle, calidad y estado. */
    private View sceneOptionRow(PlaybackOption option) {
        ResolvedPlaybackCandidate candidate = option.sourceCandidate;
        boolean available = candidate == null || candidate.isAvailable();
        String stateText = option.selected ? getString(R.string.source_selector_in_use)
                : available ? getString(R.string.source_selector_available)
                : getString(R.string.source_selector_no_signal);
        return sceneRow(option.label, option.detail,
                candidate == null ? "" : candidate.getQuality(), stateText,
                option.selected, available, candidate != null && candidate.isPreferred());
    }

    private View sceneRow(String label, String detailText, String qualityText, String stateText,
                          boolean selected, boolean available, boolean preferred) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.scene_row);
        row.setPadding(dp(13), dp(7), dp(13), dp(7));
        row.setMinimumHeight(dp(42));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        if (!available) row.setAlpha(0.42f);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setDuplicateParentStateEnabled(true);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        titleLine.setDuplicateParentStateEnabled(true);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        title.setTextColor(getColorStateList(R.color.scene_row_title));
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setDuplicateParentStateEnabled(true);
        titleLine.addView(title);
        if (preferred) {
            TextView badge = new TextView(this);
            badge.setText(R.string.source_selector_your_version);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
            badge.setLetterSpacing(0.08f);
            badge.setTextColor(getColorStateList(R.color.scene_row_detail));
            badge.setBackgroundResource(R.drawable.scene_badge);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            badge.setIncludeFontPadding(false);
            badge.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMarginStart(dp(6));
            titleLine.addView(badge, badgeParams);
        }
        text.addView(titleLine);
        if (!AppStrings.isBlank(detailText)) {
            TextView detail = new TextView(this);
            detail.setText(detailText);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            detail.setTextColor(getColorStateList(R.color.scene_row_detail));
            detail.setIncludeFontPadding(false);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(3);
            text.addView(detail, detailParams);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!AppStrings.isBlank(qualityText)) {
            TextView quality = new TextView(this);
            quality.setText(qualityText);
            quality.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            quality.setTextColor(getColorStateList(R.color.scene_row_title));
            quality.setIncludeFontPadding(false);
            quality.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            qualityParams.setMarginStart(dp(10));
            row.addView(quality, qualityParams);
        }

        TextView state = new TextView(this);
        state.setText(stateText);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        state.setTextColor(selected ? 0xFF5BE9A0 : getColor(R.color.osd_muted));
        state.setBackgroundResource(selected ? R.drawable.scene_chip_ok : R.drawable.scene_chip);
        state.setPadding(dp(7), dp(3), dp(7), dp(3));
        state.setIncludeFontPadding(false);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stateParams.setMarginStart(dp(10));
        row.addView(state, stateParams);
        return row;
    }

    private void renderDirectQualitySelector(Channel channel) {
        sourceCandidates.clear();
        playbackOptions.clear();
        // Canal con señal preferida o respaldos directos (0.5.66, varios desde la 0.5.72):
        // todas sus señales arriba y, debajo, sus calidades.
        int backupCount = TvVooBackup.directBackupsOf(channel).size();
        if (backupCount > 0) {
            ResolvedPlaybackSource principal = ResolvedPlaybackSource.direct(channel, PLAYER_USER_AGENT);
            sourceCandidates.add(ResolvedPlaybackCandidate.version(
                    getString(R.string.source_selector_direct_label),
                    getString(R.string.source_selector_direct_detail),
                    principal, "direct-principal", "", true, true, 0));
            for (int index = 0; index < backupCount; index++) {
                Channel backupChannel = TvVooBackup.directResolutionChannel(channel, index);
                if (backupChannel == null) continue;
                String label = backupCount == 1 ? getString(R.string.source_selector_backup_label)
                        : getString(R.string.source_selector_backup_numbered_label, index + 1);
                sourceCandidates.add(ResolvedPlaybackCandidate.version(
                        label,
                        getString(R.string.source_selector_backup_detail),
                        ResolvedPlaybackSource.direct(backupChannel, PLAYER_USER_AGENT),
                        "direct-backup-" + index, "", true, false, 0));
            }
            for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
                playbackOptions.add(PlaybackOption.source(
                        candidate, isCurrentSource(candidate.getSource())));
            }
        }
        List<VideoTrackOption> qualities = player == null
                ? Collections.emptyList()
                : collectVideoTrackOptions(player.getCurrentTracks());
        if (qualities.size() > 1) {
            boolean automatic = playbackPreferences != null
                    && playbackPreferences.isAutomaticQuality(channel);
            playbackOptions.add(PlaybackOption.automatic(automatic));
        }
        VideoTrackOption selected = selectedQualityOption(channel, qualities);
        for (VideoTrackOption quality : qualities) {
            playbackOptions.add(PlaybackOption.quality(
                    quality,
                    quality == selected
            ));
        }
        if (playbackOptions.isEmpty()) {
            sourceCandidateViews.clear();
            sourceSelectorOptions.removeAllViews();
            sourceSelectorStatus.setText(getString(R.string.source_selector_no_options));
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
            return;
        }
        renderPlaybackOptions(getString(
                R.string.source_selector_quality_count,
                qualities.size()
        ));
    }

    private VideoTrackOption selectedQualityOption(
            Channel channel,
            List<VideoTrackOption> qualities
    ) {
        if (channel == null || qualities.isEmpty() || playbackPreferences == null) return null;
        if (playbackPreferences.isAutomaticQuality(channel)) return null;
        PlaybackPreferences.QualityPreference preference =
                playbackPreferences.getQuality(channel);
        return preference == null
                ? qualities.get(0)
                : findClosestQuality(qualities, preference);
    }

    private boolean isCurrentSource(ResolvedPlaybackSource source) {
        if (source == null || currentPlaybackSource == null) return false;
        String currentVariant = currentPlaybackSource.getVariantId();
        String candidateVariant = source.getVariantId();
        if (!AppStrings.isBlank(currentVariant) && !AppStrings.isBlank(candidateVariant)) {
            return currentVariant.equals(candidateVariant);
        }
        URI currentUri = currentPlaybackSource.getPlaybackUri();
        URI candidateUri = source.getPlaybackUri();
        return currentUri != null && currentUri.equals(candidateUri);
    }

    private void renderPlaybackOptions(String status) {
        sourceSelectorOptions.removeAllViews();
        sourceCandidateViews.clear();
        sourceSelectorCloseFocused = false;
        for (int index = 0; index < playbackOptions.size(); index++) {
            final int candidateIndex = index;
            PlaybackOption playbackOption = playbackOptions.get(index);
            if (!classicUi) {
                View row = sceneOptionRow(playbackOption);
                row.setOnFocusChangeListener((view, focused) -> {
                    if (focused) {
                        sourceCandidateFocusIndex = candidateIndex;
                        sourceSelectorCloseFocused = false;
                    }
                });
                row.setOnClickListener(view -> selectPlaybackOption(candidateIndex));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowParams.setMargins(0, index == 0 ? 0 : dp(SOURCE_ROW_GAP_DP), 0, 0);
                rowParams.height = dp(SOURCE_ROW_HEIGHT_DP);
                sourceSelectorOptions.addView(row, rowParams);
                sourceCandidateViews.add(row);
                continue;
            }
            TextView option = new TextView(this);
            String detail = playbackOption.detail;
            String label = (playbackOption.selected ? "✓ " : "") + playbackOption.label;
            option.setText(AppStrings.isBlank(detail) ? label : label + "\n" + detail);
            option.setTextColor(getColorStateList(classicUi
                    ? R.color.classic_focus_option_text : R.color.focus_option_text));
            option.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.settings_control_text_size)
            );
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setIncludeFontPadding(false);
            option.setLineSpacing(0f, 0.95f);
            // Moderno: fila con velo cyan; clásico: botón plano de 0.5.40.
            option.setBackgroundResource(classicUi
                    ? R.drawable.classic_focus_button : R.drawable.settings_section_card);
            option.setPadding(dp(14), dp(6), dp(14), dp(6));
            option.setFocusable(true);
            option.setFocusableInTouchMode(true);
            option.setMinHeight(dp(44));
            option.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    sourceCandidateFocusIndex = candidateIndex;
                    sourceSelectorCloseFocused = false;
                }
            });
            option.setOnClickListener(view -> selectPlaybackOption(candidateIndex));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, index == 0 ? 0 : dp(5), 0, 0);
            sourceSelectorOptions.addView(option, params);
            sourceCandidateViews.add(option);
        }
        sourceSelectorStatus.setText(status);
        if (!classicUi) fitSourceSelectorList(sourceCandidateViews.size());
        if (!sourceCandidateViews.isEmpty()) {
            // El foco parte en la fila en uso, si la hay.
            int focusIndex = 0;
            for (int index = 0; index < playbackOptions.size(); index++) {
                if (playbackOptions.get(index).selected) {
                    focusIndex = index;
                    break;
                }
            }
            sourceCandidateFocusIndex = focusIndex;
            sourceCandidateViews.get(focusIndex).requestFocus();
            View last = sourceCandidateViews.get(sourceCandidateViews.size() - 1);
            last.setNextFocusDownId(sourceSelectorCloseButton.getId());
            sourceSelectorCloseButton.setNextFocusUpId(last.getId());
        } else {
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
        }
    }

    private void refreshPublishedPlaybackCatalog() {
        if (publishedPlaybackCatalogRepository == null) return;
        publishedCatalogRefreshPending = true;
        networkExecutor.execute(() -> {
            PublishedPlaybackCatalog refreshed;
            try {
                // Enlaces directos Highfly primero: así el primer canal ya abre rápido.
                PublishedHighflyLinks.update(
                        publishedPlaybackCatalogRepository.fetchHighflyLinks(),
                        System.currentTimeMillis()
                );
            } catch (Exception ignored) {
                // Sin enlaces publicados, Highfly se resuelve como siempre.
            }
            try {
                // Hermanas de cada canal TvVoo (HD, FHD, BACKUP): respaldo si la elegida cae.
                PublishedTvVooVariants.update(
                        publishedPlaybackCatalogRepository.fetchTvVooVariants(),
                        System.currentTimeMillis()
                );
            } catch (Exception ignored) {
                // Sin variantes, cada canal TvVoo usa solo su versión elegida.
            }
            try {
                refreshed = publishedPlaybackCatalogRepository.refresh();
            } catch (Exception ignored) {
                // Local selection/cache data was retired; offline startup must
                // not reintroduce channels that are absent from the web config.
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    publishedPlaybackCatalog = PublishedPlaybackCatalog.empty();
                    publishedCatalogRefreshPending = false;
                    if (getPlaylistSources().isEmpty() && !settingsOpen) {
                        openSettings();
                        return;
                    }
                    applyPlaylistsAfterCatalogWait();
                });
                return;
            }
            PublishedPlaybackCatalog result = refreshed;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                publishedPlaybackCatalog = result;
                publishedCatalogRefreshPending = false;
                mainHandler.removeCallbacks(publishedCatalogWaitTimeout);
                catalogUpdatedAtMillis = System.currentTimeMillis();
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
            });
        });
    }

    private void moveSourceSelectorFocus(int delta) {
        if (!isSourceSelectorVisible()) return;
        boolean hasClose = sourceSelectorCloseButton.getVisibility() == View.VISIBLE;
        if (sourceCandidateViews.isEmpty()) {
            if (!hasClose) return;
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
            return;
        }
        int size = sourceCandidateViews.size() + (hasClose ? 1 : 0);
        int index = sourceSelectorCloseFocused
                ? sourceCandidateViews.size()
                : Math.max(0, sourceCandidateFocusIndex);
        index = (index + delta % size + size) % size;
        if (index == sourceCandidateViews.size()) {
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
        } else {
            sourceSelectorCloseFocused = false;
            sourceCandidateFocusIndex = index;
            sourceCandidateViews.get(index).requestFocus();
        }
    }

    private void selectFocusedSource() {
        if (sourceSelectorCloseFocused) {
            closePlaybackSourceSelector();
        } else if (sourceCandidateFocusIndex >= 0
                && sourceCandidateFocusIndex < playbackOptions.size()) {
            selectPlaybackOption(sourceCandidateFocusIndex);
        }
    }

    private void selectPlaybackOption(int index) {
        if (index < 0 || index >= playbackOptions.size() || sourceCandidateTask != null) return;
        PlaybackOption option = playbackOptions.get(index);
        if (option.isQuality()) {
            Channel channel = playbackChannel;
            if (channel == null || player == null) return;
            if (option.automatic) {
                useAutomaticQuality(channel);
            } else if (option.quality != null) {
                applyFixedQuality(channel, option.quality, true);
            }
            closePlaybackSourceSelector();
            requestDiagnosticsUpdate();
            return;
        }
        if (option.sourceCandidate != null && !option.sourceCandidate.isAvailable()) return;
        int sourceIndex = sourceCandidates.indexOf(option.sourceCandidate);
        if (sourceIndex >= 0) selectSourceCandidate(sourceIndex);
    }

    private void selectSourceCandidate(int index) {
        if (sourceCandidateTask != null
                || index < 0
                || index >= sourceCandidates.size()
                || playbackChannel == null
                || player == null) return;
        ResolvedPlaybackCandidate candidate = sourceCandidates.get(index);
        if (candidate.isStale()
                || candidate.getSource() == null
                || candidate.getSource().isExpired(System.currentTimeMillis())) {
            Channel versions = TvVooBackup.hasTvVoo(playbackChannel)
                    ? TvVooBackup.resolutionChannel(playbackChannel) : playbackChannel;
            StreamResolver resolver = versions == null ? null : streamResolverRegistry.find(versions);
            if (resolver != null) startSourceSelectorQuery(playbackChannel, versions, resolver);
            return;
        }

        Channel channel = playbackChannel;
        ResolvedPlaybackSource source = candidate.getSource();
        closePlaybackSourceSelector();
        if (!isCurrentPlayback(channel, playbackGeneration)) return;
        if (TvVooBackup.hasTvVoo(channel)) {
            tvvooBackupActive = source.hasResolver();
        } else if (TvVooBackup.directBackupOf(channel) != null) {
            // Elegir una señal de respaldo la deja fija también para las reconexiones.
            int backupIndex = TvVooBackup.directBackupsOf(channel).indexOf(source.getPlaybackUri());
            tvvooBackupActive = backupIndex >= 0;
            directBackupIndex = Math.max(0, backupIndex);
        }

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
        startResolvedPlayback(channel, source, playbackGeneration, requestId);
    }

    private void closePlaybackSourceSelector() {
        sourceCandidateRequestId++;
        if (sourceCandidateContext != null) sourceCandidateContext.cancel();
        sourceCandidateContext = null;
        if (sourceCandidateTask != null) sourceCandidateTask.cancel(true);
        sourceCandidateTask = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        sourceCandidateViews.clear();
        sourceCandidateFocusIndex = -1;
        sourceSelectorCloseFocused = false;
        if (sourceSelectorOptions != null) sourceSelectorOptions.removeAllViews();
        if (sourceSelectorOverlay != null) sourceSelectorOverlay.setVisibility(View.GONE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (logoSizeAdjusting && handleLogoSizeKey(event)) return true;
        if (isGuideVisible() && handleGuideKey(event)) return true;
        if (isQualityUpgradeVisible() && handleQualityUpgradeKey(event)) return true;
        if (isSourceSelectorVisible()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getRepeatCount() == 0) {
                    closePlaybackSourceSelector();
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
                // a channel key is held. Handle every repeat so holding
                // Channel +/− (and the existing D-pad aliases) scrolls
                // through channels continuously.
                playChannel(channelIndex + channelNavigationDelta(keyCode));
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

    private void showExitDialog() {
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

    private void exitApplication() {
        if (exiting) return;
        exiting = true;
        releaseAppResources();
        closeApplicationTasksAndProcess();
    }

    private void releaseAppResources() {
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
        intent.putExtra(SettingsActivity.EXTRA_CHANNEL_INDEX, channelIndex)
                .putExtra(SettingsActivity.EXTRA_CHANNEL_TVG_ID, channel.getTvgId())
                .putExtra(SettingsActivity.EXTRA_CHANNEL_NAME, channel.getName());
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
        return intent;
    }

    private void openSettings() {
        openSettings(-1);
    }

    private void openSettings(int initialTab) {
        if (settingsOpen) return;
        mainHandler.removeCallbacks(hideOverlay);
        hideOverlay.run();
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        hideProgrammeDetail.run();
        closeGuide();
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
                        playerUsesVolumeNormalization != isVolumeNormalizationEnabled();
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
                    epgData = EpgData.empty();
                }
                // Salir de Opciones sin cambiar listas ni proveedores no recarga nada.
                refreshAfterSettings = resolverConfigurationChanged || playlistConfigurationChanged
                        || normalizationChanged || channels.isEmpty();
                openSourceSelectorAfterSettings = data != null && data.getBooleanExtra(
                        SettingsActivity.EXTRA_OPEN_SOURCE_SELECTOR, false);
                adjustLogoSizeAfterSettings = data != null && data.getBooleanExtra(
                        SettingsActivity.EXTRA_ADJUST_LOGO_SIZE, false);
            } else if (sources.isEmpty() && !hasPublishedProviderChannels()) {
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

    private String playlistSourceSignature(List<PlaylistSource> sources) {
        String signature = PlaylistSource.signature(sources);
        return AppStrings.isBlank(signature) ? "sources=none" : signature;
    }

    private String resolverSettingsSnapshot() {
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

    private boolean isChannelNavigationInverted() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_INVERT_CHANNEL_KEYS, false);
    }

    private boolean isVolumeNormalizationEnabled() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_NORMALIZE_VOLUME, false);
    }

    private boolean isAutoReconnectEnabled() {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_AUTO_RECONNECT, true);
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
        if (name == null || AppStrings.isBlank(name)) return "TV";
        StringBuilder result = new StringBuilder(2);
        for (String word : name.trim().split("\\s+")) {
            if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0)));
            if (result.length() == 2) break;
        }
        return result.length() == 0 ? "TV" : result.toString();
    }

    /** Estilo clásico (0.5.40): reloj en pastilla, carga en texto y selector en panel. */
    private void applyClassicChrome() {
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
                R.id.source_selector_scrim}) {
            View scrim = findViewById(id);
            if (scrim != null) scrim.setVisibility(View.GONE);
        }
        sourceSelectorChannel.setAllCaps(false);
        sourceSelectorChannel.setLetterSpacing(0f);
        sourceSelectorChannel.setTextColor(getColor(R.color.white));
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
        if (message == null || AppStrings.isBlank(message)) return "Error desconocido.";
        return SafePlaybackText.detail(message);
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

    /** One row in the shared source/quality selector. */
    private static final class PlaybackOption {
        final String label;
        final String detail;
        final ResolvedPlaybackCandidate sourceCandidate;
        final VideoTrackOption quality;
        final boolean automatic;
        final boolean selected;

        private PlaybackOption(
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
