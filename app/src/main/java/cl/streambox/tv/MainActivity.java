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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@UnstableApi
public final class MainActivity extends Activity {
    private static final int SETTINGS_REQUEST = 1001;
    private static final long OVERLAY_TIMEOUT_MS = 4_500;
    private static final long PLAYER_RETRY_DELAY_MS = 2_500;
    private static final long UPDATE_CHECK_DELAY_MS = 4_000;
    private static final String PLAYER_USER_AGENT = "VibeM3U/0.4.15 (Android TV)";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(2);
    private final PlaylistRepository repository = new PlaylistRepository();
    private final EpgRepository epgRepository = new EpgRepository();
    private final PlaybackRecoveryPolicy playbackRecoveryPolicy = new PlaybackRecoveryPolicy();
    private final StreamResolverRegistry streamResolverRegistry = new StreamResolverRegistry();
    private final List<Channel> channels = new ArrayList<>();

    private PlayerView playerView;
    private View channelOverlay;
    private View loadingPanel;
    private ProgressBar loadingProgress;
    private TextView loadingText;
    private TextView clock;
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
    private int channelIndex;
    private boolean loadFailed;
    private boolean settingsOpen;
    private boolean refreshAfterSettings;
    private boolean overlayAwaitingPlayback;
    private boolean exiting;
    private Dialog exitDialog;
    private Dialog qualityDialog;
    private LinearLayout qualityDialogOptions;
    private Switch qualityDialogSubtitleSwitch;
    private String qualityDialogChannelIdentity;
    private String qualityPreferenceAppliedFor;
    private String subtitlePreferenceAppliedFor;
    private String subtitleTextObservedFor;
    private int playlistGeneration;
    private boolean playerUsesVolumeNormalization;
    private long playbackGeneration;
    private Runnable scheduledPlaybackRetry;
    private Future<?> playbackResolutionTask;
    private long playbackResolutionRequestId;
    private Channel playbackChannel;
    private ResolvedPlaybackSource currentPlaybackSource;
    private boolean tokenRefreshAttempted;
    private boolean fallbackAttempted;

    private final Runnable hideOverlay = () -> {
        channelOverlay.setVisibility(View.GONE);
        clock.setVisibility(View.GONE);
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
        channelLogoCache = new ChannelLogoCache(this);
        playbackPreferences = new PlaybackPreferences(this);
        bindViews();
        registerBackCallback();
        enterImmersiveMode();
        createPlayer();
        appUpdater = new AppUpdater(this, networkExecutor, mainHandler);
        mainHandler.postDelayed(appUpdater::checkForUpdates, UPDATE_CHECK_DELAY_MS);
        updateClock.run();
    }

    private void bindViews() {
        playerView = findViewById(R.id.player_view);
        channelOverlay = findViewById(R.id.channel_overlay);
        loadingPanel = findViewById(R.id.loading_panel);
        loadingProgress = findViewById(R.id.loading_progress);
        loadingText = findViewById(R.id.loading_text);
        clock = findViewById(R.id.clock);
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
                if (isProviderAuthorizationError(error)) {
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
        int generation = ++playlistGeneration;
        playbackGeneration++;
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        playbackRecoveryPolicy.reset();
        playbackChannel = null;
        currentPlaybackSource = null;
        loadFailed = false;
        loadingPanel.setVisibility(View.VISIBLE);
        loadingProgress.setVisibility(View.VISIBLE);
        loadingText.setText(R.string.loading_playlist);
        epgData = EpgData.empty();
        mainHandler.removeCallbacks(updateProgramme);
        hideOverlay.run();

        if (!isNetworkAvailable()) {
            showPlaylistError("No hay conexión a Internet.");
            return;
        }

        networkExecutor.submit(() -> {
            try {
                Playlist downloaded = repository.download(url);
                mainHandler.post(() -> {
                    if (generation != playlistGeneration || isFinishing()) return;
                    channels.clear();
                    channels.addAll(downloaded.getChannels());
                    channelIndex = playbackPreferences.findInitialChannelIndex(channels);
                    loadingPanel.setVisibility(View.GONE);
                    playChannel(channelIndex);
                });

                if (downloaded.getEpgUri() != null) {
                    try {
                        EpgData downloadedEpg = epgRepository.download(downloaded.getEpgUri());
                        mainHandler.post(() -> {
                            if (generation != playlistGeneration || isFinishing()) return;
                            epgData = downloadedEpg;
                            mainHandler.removeCallbacks(updateProgramme);
                            updateProgramme.run();
                        });
                    } catch (Exception ignored) {
                        // La reproducción continúa usando el grupo del canal como respaldo.
                    }
                }
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (generation != playlistGeneration || isFinishing()) return;
                    showPlaylistError(shortMessage(error));
                });
            }
        });
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
        if (channels.isEmpty()) return;
        channelIndex = (requestedIndex % channels.size() + channels.size()) % channels.size();
        Channel channel = channels.get(channelIndex);
        playbackGeneration++;
        cancelScheduledPlaybackRetry();
        cancelPlaybackResolution();
        playbackRecoveryPolicy.reset();
        playbackChannel = channel;
        currentPlaybackSource = null;
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
        loadChannelLogo(channel);
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
        if (playbackResolutionTask != null) {
            playbackResolutionTask.cancel(true);
            playbackResolutionTask = null;
        }
    }

    private void resolveAndPlay(Channel channel, long expectedGeneration) {
        if (player == null || !isCurrentPlayback(channel, expectedGeneration)) return;
        StreamResolver resolver = streamResolverRegistry.find(channel);
        if (resolver == null) {
            startResolvedPlayback(
                    channel,
                    ResolvedPlaybackSource.direct(channel, PLAYER_USER_AGENT),
                    expectedGeneration
            );
            return;
        }

        cancelPlaybackResolution();
        long requestId = ++playbackResolutionRequestId;
        playbackResolutionTask = networkExecutor.submit(() -> {
            try {
                ResolvedPlaybackSource source = resolver.resolve(channel);
                mainHandler.post(() -> {
                    if (!isCurrentPlayback(channel, expectedGeneration)
                            || requestId != playbackResolutionRequestId) return;
                    playbackResolutionTask = null;
                    startResolvedPlayback(channel, source, expectedGeneration);
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
                expectedGeneration
        );
    }

    private void startResolvedPlayback(
            Channel channel,
            ResolvedPlaybackSource source,
            long expectedGeneration
    ) {
        if (player == null || !isCurrentPlayback(channel, expectedGeneration)) return;
        currentPlaybackSource = source;
        player.setMediaSource(mediaSourceFor(channel, source));
        prepareAndPlay();
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
        return !isFinishing()
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
            codecInfo.setText("Renovando autorización");
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            resolveAndPlay(channel, expectedGeneration);
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
                    expectedGeneration
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
                currentPlaybackSource = null;
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
    }

    private void loadChannelLogo(Channel channel) {
        URI logoUri = channel.getLogoUri();
        String fallback = initials(channel.getName());
        channelLogo.setImageDrawable(null);
        channelLogo.setVisibility(View.GONE);
        channelLogoFallback.setText(fallback);
        channelLogoFallback.setVisibility(View.VISIBLE);
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
        networkExecutor.submit(() -> {
            try {
                android.graphics.Bitmap bitmap = channelLogoCache.load(
                        logoUri,
                        targetWidthPx,
                        targetHeightPx
                );
                mainHandler.post(() -> {
                    if (expectedIndex != channelIndex || isFinishing()) return;
                    channelLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    channelLogo.setImageBitmap(bitmap);
                    channelLogo.setVisibility(View.VISIBLE);
                    channelLogoFallback.setVisibility(View.GONE);
                });
            } catch (Exception ignored) {
                // El monograma del canal permanece visible como respaldo.
            }
        });
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
            qualityPreferenceAppliedFor = channelIdentity;
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
        maybeAddSubtitleOptionToQualityDialog();
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

    private void showStreamQualityDialog() {
        if (player == null || channels.isEmpty()
                || channelIndex < 0 || channelIndex >= channels.size()) return;
        if (qualityDialog != null && qualityDialog.isShowing()) return;

        Channel channel = channels.get(channelIndex);
        List<VideoTrackOption> options =
                collectVideoTrackOptions(player.getCurrentTracks());
        PlaybackPreferences.QualityPreference preference =
                playbackPreferences.getQuality(channel);
        VideoTrackOption selectedOption = preference == null
                ? null
                : findClosestQuality(options, preference);

        Dialog dialog = new Dialog(this);
        qualityDialog = dialog;
        dialog.setContentView(R.layout.dialog_stream_quality);
        dialog.setCanceledOnTouchOutside(false);

        TextView description = dialog.findViewById(
                R.id.stream_quality_description
        );
        description.setText(getString(
                options.size() <= 1
                        ? R.string.stream_quality_unavailable
                        : R.string.stream_quality_description,
                channel.getName()
        ));

        LinearLayout container = dialog.findViewById(
                R.id.stream_quality_options
        );
        qualityDialogOptions = container;
        qualityDialogSubtitleSwitch = null;
        qualityDialogChannelIdentity = PlaybackPreferences.channelIdentity(channel);
        View focusTarget = null;
        Button automaticButton = null;
        if (hasObservedSubtitleText(channel)) {
            qualityDialogSubtitleSwitch = createSubtitleSwitch(channel);
            container.addView(qualityDialogSubtitleSwitch);
            focusTarget = qualityDialogSubtitleSwitch;
        }

        if (options.size() != 1) {
            automaticButton = createQualityButton(
                    (preference == null ? "\u2713 " : "")
                            + getString(R.string.stream_quality_automatic)
            );
            automaticButton.setOnClickListener(view -> {
                useAutomaticQuality(channel);
                dialog.dismiss();
            });
            container.addView(automaticButton);
            if (preference == null) focusTarget = automaticButton;
        }

        for (VideoTrackOption option : options) {
            boolean selected = option == selectedOption;
            Button button = createQualityButton(
                    (selected ? "\u2713 " : "") + option.label()
            );
            button.setOnClickListener(view -> {
                applyFixedQuality(channel, option, true);
                dialog.dismiss();
            });
            container.addView(button);
            if (selected || options.size() == 1) focusTarget = button;
        }

        if (focusTarget == null) focusTarget = automaticButton;
        if (focusTarget != null) {
            View initialFocus = focusTarget;
            dialog.setOnShowListener(ignored -> initialFocus.requestFocus());
        }
        dialog.setOnDismissListener(ignored -> {
            if (qualityDialog == dialog) {
                qualityDialog = null;
                qualityDialogOptions = null;
                qualityDialogSubtitleSwitch = null;
                qualityDialogChannelIdentity = null;
            }
            if (!isFinishing() && !isDestroyed()) enterImmersiveMode();
        });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.68f;
            window.setAttributes(attributes);
        }
        dialog.show();
    }

    private void maybeAddSubtitleOptionToQualityDialog() {
        if (qualityDialog == null
                || !qualityDialog.isShowing()
                || qualityDialogOptions == null
                || qualityDialogSubtitleSwitch != null
                || player == null
                || channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return;

        Channel channel = channels.get(channelIndex);
        String channelIdentity = PlaybackPreferences.channelIdentity(channel);
        if (!channelIdentity.equals(qualityDialogChannelIdentity)
                || !channelIdentity.equals(subtitleTextObservedFor)) return;

        qualityDialogSubtitleSwitch = createSubtitleSwitch(channel);
        qualityDialogOptions.addView(qualityDialogSubtitleSwitch, 0);
    }

    private Switch createSubtitleSwitch(Channel channel) {
        Switch subtitlesSwitch = new Switch(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
        );
        params.bottomMargin = dp(4);
        subtitlesSwitch.setLayoutParams(params);
        subtitlesSwitch.setBackgroundResource(R.drawable.focus_button_compact);
        subtitlesSwitch.setPadding(dp(12), 0, dp(8), 0);
        subtitlesSwitch.setTextColor(getColor(R.color.white));
        subtitlesSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        subtitlesSwitch.setAllCaps(false);
        subtitlesSwitch.setChecked(playbackPreferences.getSubtitles(channel));
        updateSubtitleSwitchLabel(subtitlesSwitch);
        subtitlesSwitch.setOnCheckedChangeListener((button, checked) -> {
            playbackPreferences.rememberSubtitles(channel, checked);
            updateSubtitleSwitchLabel(button);
            subtitlePreferenceAppliedFor = null;
            applySavedSubtitlePreference(player.getCurrentTracks());
        });
        return subtitlesSwitch;
    }

    private void updateSubtitleSwitchLabel(CompoundButton button) {
        button.setText(button.isChecked()
                ? R.string.subtitles_enabled
                : R.string.subtitles_disabled);
    }

    private Button createQualityButton(String text) {
        Button button = new Button(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
        );
        params.bottomMargin = dp(6);
        button.setLayoutParams(params);
        button.setBackgroundResource(R.drawable.focus_button_compact);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setText(text);
        button.setTextColor(getColor(R.color.white));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        button.setAllCaps(false);
        return button;
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
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
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                    playChannel(channelIndex + (isChannelNavigationInverted() ? 1 : -1));
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    playChannel(channelIndex + (isChannelNavigationInverted() ? -1 : 1));
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    showStreamQualityDialog();
                    return true;
                }
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

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBackAction);
        }
    }

    private void handleBackAction() {
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
        if (exitDialog != null) exitDialog.dismiss();
        playlistGeneration++;
        playbackGeneration++;
        cancelPlaybackResolution();
        mainHandler.removeCallbacksAndMessages(null);
        if (player != null) player.pause();

        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager == null || activityManager.getAppTasks().isEmpty()) {
            finishAndRemoveTask();
            return;
        }
        for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
            task.finishAndRemoveTask();
        }
    }

    private void openSettings() {
        if (settingsOpen) return;
        settingsOpen = true;
        startActivityForResult(new Intent(this, SettingsActivity.class), SETTINGS_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (appUpdater != null && appUpdater.onActivityResult(requestCode)) return;
        if (requestCode == SETTINGS_REQUEST) {
            settingsOpen = false;
            String url = getPlaylistUrl();
            if (resultCode == RESULT_OK && !url.isBlank()) {
                if (playerUsesVolumeNormalization != isVolumeNormalizationEnabled()) {
                    if (player != null) {
                        player.release();
                        player = null;
                    }
                    createPlayer();
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
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return "Error desconocido.";
        return message
                .replaceAll("(?i)https?://[^\\s]+", "URL")
                .replaceAll("(?i)(access_token|token|serverKey)=([^&\\s]+)", "$1=[oculto]");
    }

    private boolean isProviderAuthorizationError(PlaybackException error) {
        if (currentPlaybackSource == null || !currentPlaybackSource.hasResolver()) {
            return false;
        }
        int responseCode = httpResponseCode(error);
        return responseCode == 401 || responseCode == 403;
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
        cancelScheduledPlaybackRetry();
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        playlistGeneration++;
        playbackGeneration++;
        cancelPlaybackResolution();
        mainHandler.removeCallbacksAndMessages(null);
        if (qualityDialog != null) qualityDialog.dismiss();
        if (appUpdater != null) appUpdater.destroy();
        networkExecutor.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
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
