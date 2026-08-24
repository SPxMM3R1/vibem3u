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
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@UnstableApi
public final class MainActivity extends Activity {
    private static final int SETTINGS_REQUEST = 1001;
    private static final long OVERLAY_TIMEOUT_MS = 4_500;
    private static final long LIGHT_EPG_TIMEOUT_MS = 6_500;
    private static final long PLAYER_RETRY_DELAY_MS = 2_500;
    private static final long UPDATE_CHECK_DELAY_MS = 4_000;
    private static final long NO_RESOLUTION_REQUEST = -1L;
    private static final String PLAYER_USER_AGENT = "VibeM3U/0.4.32 (Android TV)";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService logoCacheExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService resourceCacheExecutor = Executors.newSingleThreadExecutor();
    private PlaylistRepository repository;
    private EpgRepository epgRepository;
    private final PlaybackRecoveryPolicy playbackRecoveryPolicy = new PlaybackRecoveryPolicy();
    private final ResolverCoordinator resolverCoordinator = new ResolverCoordinator();
    private ResolverCatalogRepository resolverCatalogRepository;
    private ResolverPreferences resolverPreferences;
    private StreamResolverRegistry streamResolverRegistry;
    private Map<String, Integer> resolverChannelCounts = Collections.emptyMap();
    private final List<Channel> channels = new ArrayList<>();

    private PlayerView playerView;
    private View channelOverlay;
    private View loadingPanel;
    private ProgressBar loadingProgress;
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
    private TextView contentTitle;
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
    private String loadedPlaylistUrl = "";
    private String activeEpgUrl = "";
    private int channelIndex;
    private boolean loadFailed;
    private boolean settingsOpen;
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
    private String displayedLogoIdentity = "";
    private final Set<String> logoRevalidatedThisSession = new HashSet<>();
    private boolean playerUsesVolumeNormalization;
    private long playbackGeneration;
    private Runnable scheduledPlaybackRetry;
    private Future<?> playbackResolutionTask;
    private long playbackResolutionRequestId;
    private long activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    private Channel playbackChannel;
    private ResolvedPlaybackSource currentPlaybackSource;
    private boolean tokenRefreshAttempted;
    private boolean fallbackAttempted;

    private final Runnable hideOverlay = () -> {
        channelOverlay.setVisibility(View.GONE);
        clock.setVisibility(View.GONE);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repository = new PlaylistRepository(this);
        epgRepository = new EpgRepository(this);
        channelLogoCache = new ChannelLogoCache(this);
        playbackPreferences = new PlaybackPreferences(this);
        resolverCatalogRepository = new ResolverCatalogRepository(this);
        resolverPreferences = new ResolverPreferences(this);
        reloadResolverRegistry();
        bindViews();
        registerBackCallback();
        enterImmersiveMode();
        createPlayer();
        appUpdater = new AppUpdater(this, networkExecutor, mainHandler);
        mainHandler.postDelayed(appUpdater::checkForUpdates, UPDATE_CHECK_DELAY_MS);
        updateClock.run();
    }

    private void reloadResolverRegistry() {
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
        loadingPanel = findViewById(R.id.loading_panel);
        loadingProgress = findViewById(R.id.loading_progress);
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
        playerUsesVolumeNormalization = isVolumeNormalizationEnabled();
        DefaultHttpDataSource.Factory httpDataSourceFactory =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent(PLAYER_USER_AGENT)
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(12_000)
                        .setReadTimeoutMs(20_000);
        player = new ExoPlayer.Builder(
                this,
                new VibeRenderersFactory(this, playerUsesVolumeNormalization)
        )
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpDataSourceFactory))
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                        true
                )
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                updateStreamStatus(playbackState);
                updateDiagnostics();
            }

            @Override public void onVideoSizeChanged(VideoSize videoSize) {
                updateDiagnostics();
            }

            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                updateDiagnostics();
                applySavedQualityPreference(tracks);
                applySavedSubtitlePreference(tracks);
            }

            @Override public void onCues(CueGroup cueGroup) {
                handleSubtitleCues(cueGroup);
            }

            @Override public void onPlayerError(PlaybackException error) {
                setStatus("ERROR", R.color.red);
                codecInfo.setText(shortMessage(error));
                overlayAwaitingPlayback = true;
                showOverlay(true);
                cancelScheduledPlaybackRetry();
                if (isProviderRefreshError(error)) {
                    handleProviderAuthorizationFailure();
                    return;
                }
                MediaItem current = player == null ? null : player.getCurrentMediaItem();
                if (current != null && playbackRecoveryPolicy.tryConsumeRetry(error.errorCode)) {
                    schedulePlaybackRetry(current.mediaId, playbackGeneration);
                }
            }
        });
    }

    private void refreshPlaylist(String url) {
        boolean sourceChanged = !url.equals(loadedPlaylistUrl);
        boolean keepCurrentUi = !sourceChanged && !channels.isEmpty();
        EpgData visibleEpg = keepCurrentUi && epgData.getProgrammeCount() > 0
                ? epgData
                : null;
        String visibleEpgUrl = visibleEpg == null ? "" : activeEpgUrl;
        boolean resetPlayback = sourceChanged || channels.isEmpty();
        int generation = ++playlistGeneration;
        playbackGeneration++;
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        playbackRecoveryPolicy.reset();
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
            loadingPanel.setVisibility(View.VISIBLE);
            loadingProgress.setVisibility(View.VISIBLE);
            loadingText.setText(R.string.loading_playlist);
        }
        if (sourceChanged) {
            channels.clear();
            channelIndex = 0;
            epgData = EpgData.empty();
            activeEpgUrl = "";
            mainHandler.removeCallbacks(updateProgramme);
        }
        if (!keepCurrentUi) {
            hideOverlay.run();
        }

        boolean existingPlaylist = !channels.isEmpty();
        resourceCacheExecutor.submit(() -> {
            boolean usablePlaylist = existingPlaylist;
            EpgData cachedEpg = visibleEpg;
            String cachedEpgUrl = visibleEpgUrl;
            try {
                if (!keepCurrentUi) {
                    Playlist cached = repository.loadCached(url);
                    if (cached != null) {
                        usablePlaylist = true;
                        Playlist cachedPlaylist = cached;
                        mainHandler.post(() -> applyPlaylist(
                                cachedPlaylist,
                                url,
                                generation,
                                false
                        ));
                        cachedEpgUrl = epgUrl(cachedPlaylist);
                        URI cachedEpgUri = cachedPlaylist.getEpgUri();
                        if (cachedEpgUri != null) {
                            try {
                                cachedEpg = epgRepository.loadCached(cachedEpgUri);
                            } catch (Exception ignored) {
                                cachedEpg = null;
                            }
                            EpgData dataToShow = cachedEpg;
                            if (dataToShow != null) {
                                mainHandler.post(() -> applyEpgData(
                                        dataToShow,
                                        cachedEpgUri,
                                        generation
                                ));
                            }
                        }
                    }
                }

                if (!isNetworkAvailable()) {
                    boolean hasUsablePlaylist = usablePlaylist;
                    mainHandler.post(() -> {
                        if (generation != playlistGeneration || isFinishing()) return;
                        if (hasUsablePlaylist) {
                            loadingPanel.setVisibility(View.GONE);
                        } else {
                            showPlaylistError("No hay conexión a Internet.");
                        }
                    });
                    return;
                }

                refreshPlaylistFromNetwork(
                        url,
                        generation,
                        usablePlaylist,
                        existingPlaylist,
                        cachedEpg,
                        cachedEpgUrl
                );
            } catch (Exception error) {
                boolean hasUsablePlaylist = usablePlaylist || existingPlaylist;
                mainHandler.post(() -> {
                    if (generation != playlistGeneration || isFinishing()) return;
                    if (hasUsablePlaylist) {
                        loadingPanel.setVisibility(View.GONE);
                    } else {
                        showPlaylistError(shortMessage(error));
                    }
                });
            }
        });
    }

    private void refreshPlaylistFromNetwork(
            String url,
            int generation,
            boolean usablePlaylist,
            boolean existingPlaylist,
            EpgData cachedEpg,
            String cachedEpgUrl
    ) {
        networkExecutor.submit(() -> {
            try {
                PlaylistRepository.LoadResult result = repository.downloadIfChanged(url);
                mainHandler.post(() -> {
                    if (generation != playlistGeneration || isFinishing()) return;
                    if (result.isChanged() || channels.isEmpty()) {
                        applyPlaylist(
                                result.getPlaylist(),
                                url,
                                generation,
                                result.isChanged()
                        );
                    } else {
                        loadingPanel.setVisibility(View.GONE);
                    }
                    String resultEpgUrl = epgUrl(result.getPlaylist());
                    loadEpgForPlaylist(
                            result.getPlaylist(),
                            generation,
                            cachedEpgUrl.equals(resultEpgUrl) ? cachedEpg : null
                    );
                });
            } catch (Exception error) {
                boolean hasUsablePlaylist = usablePlaylist || existingPlaylist;
                mainHandler.post(() -> {
                    if (generation != playlistGeneration || isFinishing()) return;
                    if (hasUsablePlaylist) {
                        loadingPanel.setVisibility(View.GONE);
                    } else {
                        showPlaylistError(shortMessage(error));
                    }
                });
            }
        });
    }

    private void loadEpgForPlaylist(Playlist playlist, int generation, EpgData cached) {
        URI epgUri = playlist.getEpgUri();
        if (epgUri == null) return;
        resourceCacheExecutor.submit(() -> {
            EpgData local = cached;
            try {
                if (local == null) {
                    local = epgRepository.loadCached(epgUri);
                    if (local != null) {
                        EpgData cachedData = local;
                        mainHandler.post(() -> applyEpgData(cachedData, epgUri, generation));
                    }
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
            } catch (Exception ignored) {
                // La reproducción continúa usando el grupo del canal como respaldo.
            }
        });
    }

    private static String epgUrl(Playlist playlist) {
        URI epgUri = playlist == null ? null : playlist.getEpgUri();
        return epgUri == null ? "" : epgUri.toString();
    }

    private void applyEpgData(EpgData data, URI epgUri, int generation) {
        if (generation != playlistGeneration || isFinishing()) return;
        String expectedUrl = epgUri == null ? "" : epgUri.toString();
        if (!expectedUrl.equals(activeEpgUrl)) return;
        epgData = data == null ? EpgData.empty() : data;
        mainHandler.removeCallbacks(updateProgramme);
        updateProgramme.run();
    }

    private void applyPlaylist(
            Playlist playlist,
            String sourceUrl,
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

        List<Channel> sourceChannels = playlist.getChannels();
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

        channelIndex = hadChannels
                ? PlaybackPreferences.findChannelIndex(
                        channels,
                        previousIdentity,
                        channelIndex
                )
                : playbackPreferences.findInitialChannelIndex(channels);
        loadedPlaylistUrl = sourceUrl;

        String nextEpgUrl = playlist.getEpgUri() == null
                ? ""
                : playlist.getEpgUri().toString();
        if (!nextEpgUrl.equals(activeEpgUrl)) {
            activeEpgUrl = nextEpgUrl;
            epgData = EpgData.empty();
            mainHandler.removeCallbacks(updateProgramme);
        }

        loadingPanel.setVisibility(View.GONE);
        Channel selectedChannel = channels.get(channelIndex);
        boolean sameChannel = previousChannel != null
                && previousIdentity.equals(PlaybackPreferences.channelIdentity(selectedChannel));
        boolean streamChanged = sameChannel
                && previousStreamUri != null
                && !previousStreamUri.equals(selectedChannel.getStreamUri());
        StreamResolver resolver = streamResolverRegistry.find(selectedChannel);
        boolean resolverNeedsResolution = resolver != null
                && (currentPlaybackSource == null
                || !currentPlaybackSource.isDynamicallyResolved());

        if (!hadChannels || !sameChannel || streamChanged || resolverNeedsResolution) {
            playChannel(channelIndex, contentChanged);
        } else {
            // The source may have been resolved for the previous Channel
            // object. Keep the fresh in-memory source, but associate it with
            // the current playlist object for future retries.
            playbackChannel = selectedChannel;
            channelNumber.setText(String.format(Locale.ROOT, "%03d", channelIndex + 1));
            channelName.setText(selectedChannel.getName());
            updateProgrammeInfo();
            loadChannelLogo(selectedChannel, contentChanged);
        }
    }

    private void showPlaylistError(String detail) {
        loadFailed = true;
        loadingPanel.setVisibility(View.VISIBLE);
        loadingProgress.setVisibility(View.GONE);
        String message = getString(R.string.playlist_error);
        if (detail != null && !detail.isBlank()) {
            message += "\n\n" + detail;
        }
        loadingText.setText(message);
    }

    private void playChannel(int requestedIndex) {
        playChannel(requestedIndex, false);
    }

    private void playChannel(int requestedIndex, boolean revalidateLogo) {
        if (channels.isEmpty()) return;
        channelIndex = (requestedIndex % channels.size() + channels.size()) % channels.size();
        Channel channel = channels.get(channelIndex);
        playbackGeneration++;
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        playbackRecoveryPolicy.reset();
        playbackChannel = channel;
        discardCurrentPlaybackSource();
        tokenRefreshAttempted = false;
        fallbackAttempted = false;
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
        resolveAndPlay(channel, playbackGeneration);
        playbackPreferences.rememberChannel(channel, channelIndex);

        channelNumber.setText(String.format(Locale.ROOT, "%03d", channelIndex + 1));
        channelName.setText(channel.getName());
        updateProgrammeInfo();
        videoInfo.setText("Resolución pendiente");
        codecInfo.setText("Analizando stream…");
        setStatus("CARGANDO", R.color.amber);
        loadChannelLogo(channel, revalidateLogo);
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
        playbackResolutionTask = networkExecutor.submit(() -> {
            try {
                ResolvedPlaybackSource source = resolverCoordinator.resolve(
                        channel,
                        resolver,
                        forceRefresh
                );
                mainHandler.post(() -> {
                    if (!isCurrentPlayback(channel, expectedGeneration)
                            || requestId != playbackResolutionRequestId) return;
                    playbackResolutionTask = null;
                    startResolvedPlayback(channel, source, expectedGeneration, requestId);
                });
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) return;
                mainHandler.post(() -> {
                    if (!isCurrentPlayback(channel, expectedGeneration)
                            || requestId != playbackResolutionRequestId) return;
                    playbackResolutionTask = null;
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
        if (fallbackAttempted) {
            showPlaybackFailure();
            return;
        }
        tokenRefreshAttempted = true;
        fallbackAttempted = true;
        codecInfo.setText("Probando respaldo del canal");
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
        activePlaybackSourceRequestId = source.isDynamicallyResolved()
                ? expectedResolutionRequestId
                : NO_RESOLUTION_REQUEST;
        currentPlaybackSource = source;
        player.setMediaSource(mediaSourceFor(channel, source));
        prepareAndPlay();
    }

    private void discardCurrentPlaybackSource() {
        currentPlaybackSource = null;
        activePlaybackSourceRequestId = NO_RESOLUTION_REQUEST;
    }

    private MediaSource mediaSourceFor(Channel channel, ResolvedPlaybackSource source) {
        String userAgent = source.getUserAgent().isBlank()
                ? PLAYER_USER_AGENT
                : source.getUserAgent();
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000);
        Map<String, String> headers = source.getRequestHeaders();
        if (!headers.isEmpty()) {
            dataSourceFactory.setDefaultRequestProperties(headers);
        }
        return new DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(mediaItemFor(channel, source.getPlaybackUri()));
    }

    private boolean isCurrentPlayback(Channel channel, long expectedGeneration) {
        return !exiting
                && !isFinishing()
                && expectedGeneration == playbackGeneration
                && playbackChannel == channel;
    }

    private void handleProviderAuthorizationFailure() {
        if (playbackChannel == null || currentPlaybackSource == null
                || !currentPlaybackSource.hasResolver()) {
            showPlaybackFailure();
            return;
        }

        Channel channel = playbackChannel;
        long expectedGeneration = playbackGeneration;
        if (!tokenRefreshAttempted) {
            tokenRefreshAttempted = true;
            setStatus("RENOVANDO", R.color.amber);
            codecInfo.setText("Renovando fuente");
            // Drop the rejected URL before requesting the replacement token.
            // It must not be available to a generic retry path.
            discardCurrentPlaybackSource();
            StreamResolver resolver = streamResolverRegistry.find(channel);
            resolverCoordinator.invalidate(channel, resolver);
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            resolveAndPlay(channel, expectedGeneration, true);
            return;
        }

        if (!fallbackAttempted) {
            fallbackAttempted = true;
            setStatus("RESPALDO", R.color.amber);
            codecInfo.setText("Probando respaldo del canal");
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
        setStatus("ERROR", R.color.red);
        codecInfo.setText("Canal no disponible");
        overlayAwaitingPlayback = true;
        showOverlay(true);
    }

    private void retryCurrentPlayback(String expectedMediaId, long expectedGeneration) {
        if (player == null || expectedGeneration != playbackGeneration) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current == null || !expectedMediaId.equals(current.mediaId)) return;

        // A resolver-backed channel must never retry the old MediaItem URL:
        // that URL contains the previous short-lived token. Resolve again for
        // every automatic retry, including errors that are not 401/403.
        if (playbackChannel != null && streamResolverRegistry.find(playbackChannel) != null) {
            StreamResolver resolver = streamResolverRegistry.find(playbackChannel);
            resolverCoordinator.invalidate(playbackChannel, resolver);
            player.stop();
            player.clearMediaItems();
            discardCurrentPlaybackSource();
            resolveAndPlay(playbackChannel, expectedGeneration, true);
            return;
        }

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
                tokenRefreshAttempted = false;
                fallbackAttempted = false;
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
        logoCacheExecutor.submit(() -> {
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
            networkExecutor.submit(() -> {
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

    private void updateDiagnostics() {
        if (player == null) return;
        Format video = player.getVideoFormat();
        Format audio = player.getAudioFormat();

        if (video != null) {
            String resolution = video.width > 0 && video.height > 0
                    ? video.width + " × " + video.height
                    : "Resolución desconocida";
            String fps = video.frameRate > 0 ? " · " + trimDecimal(video.frameRate) + " FPS" : "";
            videoInfo.setText(resolution + fps);
        }

        String videoCodec = codecName(video == null ? null : video.sampleMimeType);
        String audioCodec = codecName(audio == null ? null : audio.sampleMimeType);
        int bitrate = video != null && video.averageBitrate > 0 ? video.averageBitrate :
                (video != null ? video.peakBitrate : Format.NO_VALUE);
        String bitrateText = bitrate > 0
                ? String.format(Locale.ROOT, " · %.1f Mbps", bitrate / 1_000_000f)
                : "";
        codecInfo.setText(videoCodec + " · " + audioCodec + bitrateText);
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
        channelOverlay.setVisibility(View.GONE);
        clock.setVisibility(View.GONE);
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
                        refreshPlaylist(getPlaylistUrl());
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
        playlistGeneration++;
        playbackGeneration++;
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        resolverCoordinator.clear();
        mainHandler.removeCallbacksAndMessages(null);

        if (exitDialog != null) {
            exitDialog.dismiss();
            exitDialog = null;
        }
        if (appUpdater != null) {
            appUpdater.destroy();
            appUpdater = null;
        }
        networkExecutor.shutdownNow();
        logoCacheExecutor.shutdownNow();
        resourceCacheExecutor.shutdownNow();

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
            resolverCounts.add(resolverChannelCounts.getOrDefault(definition.getId(), 0));
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
            String url = getPlaylistUrl();
            if (resultCode == RESULT_OK && !url.isBlank()) {
                applyPlaybackSettingsResult(data);
                resolverCoordinator.clear();
                reloadResolverRegistry();
                if (playerUsesVolumeNormalization != isVolumeNormalizationEnabled()) {
                    if (player != null) {
                        player.release();
                        player = null;
                    }
                    createPlayer();
                    channels.clear();
                    loadedPlaylistUrl = "";
                    activeEpgUrl = "";
                    epgData = EpgData.empty();
                }
                refreshAfterSettings = true;
            } else if (url.isBlank()) {
                openSettings();
            }
        }
    }

    private String getPlaylistUrl() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        String url = prefs.getString(SettingsActivity.KEY_PLAYLIST_URL, "");
        return url == null ? "" : url.trim();
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
        if (responseCode == 404) return "Fuente caducada.";
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return "Error desconocido.";
        return message
                .replaceAll("(?i)https?://[^\\s]+", "URL")
                .replaceAll("(?i)(access_token|token|serverKey)=([^&\\s]+)", "$1=[oculto]");
    }

    private boolean isProviderRefreshError(PlaybackException error) {
        if (currentPlaybackSource == null || !currentPlaybackSource.hasResolver()) {
            return false;
        }
        if (currentPlaybackSource.isDynamicallyResolved()
                && activePlaybackSourceRequestId != playbackResolutionRequestId) {
            // Ignore an authorization error from a Media3 item that was
            // superseded while its callback was still being delivered.
            return false;
        }
        int responseCode = httpResponseCode(error);
        return responseCode == 401 || responseCode == 403 || responseCode == 404;
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
            String url = getPlaylistUrl();
            if (url.isBlank()) {
                openSettings();
            } else {
                refreshPlaylist(url);
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
            refreshPlaylist(getPlaylistUrl());
        }
        startPlaybackFromInput();
    }

    @Override
    protected void onPause() {
        if (appUpdater != null) appUpdater.onHostPause();
        mainHandler.removeCallbacks(hideLightEpg);
        hideLightEpg.run();
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
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseAppResources();
        super.onDestroy();
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
