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

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends Activity {
    private static final int SETTINGS_REQUEST = 1001;
    private static final long OVERLAY_TIMEOUT_MS = 4_500;
    private static final long PLAYER_RETRY_DELAY_MS = 2_500;
    private static final long UPDATE_CHECK_DELAY_MS = 4_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(2);
    private final PlaylistRepository repository = new PlaylistRepository();
    private final EpgRepository epgRepository = new EpgRepository();
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
    private ChannelLogoCache channelLogoCache;
    private EpgData epgData = EpgData.empty();
    private int channelIndex;
    private boolean loadFailed;
    private boolean settingsOpen;
    private boolean refreshAfterSettings;
    private boolean overlayAwaitingPlayback;
    private boolean exiting;
    private Dialog exitDialog;
    private int playlistGeneration;

    private final Runnable hideOverlay = () -> {
        channelOverlay.setVisibility(View.GONE);
        clock.setVisibility(View.GONE);
    };
    private final Runnable updateClock = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
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
        player = new ExoPlayer.Builder(this).build();
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
            }

            @Override public void onPlayerError(PlaybackException error) {
                setStatus("ERROR", R.color.red);
                codecInfo.setText(shortMessage(error));
                overlayAwaitingPlayback = true;
                showOverlay(true);
                mainHandler.postDelayed(() -> {
                    if (player != null && player.getPlayerError() != null) {
                        player.prepare();
                        player.play();
                    }
                }, PLAYER_RETRY_DELAY_MS);
            }
        });
    }

    private void refreshPlaylist(String url) {
        int generation = ++playlistGeneration;
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
                    channelIndex = 0;
                    loadingPanel.setVisibility(View.GONE);
                    playChannel(0);
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

        player.setMediaItem(MediaItem.fromUri(Uri.parse(channel.getStreamUri().toString())));
        player.prepare();
        player.play();

        channelNumber.setText(String.format(Locale.ROOT, "%03d", channelIndex + 1));
        channelName.setText(channel.getName());
        updateProgrammeInfo();
        videoInfo.setText("Resolución pendiente");
        codecInfo.setText("Analizando stream…");
        setStatus("CARGANDO", R.color.amber);
        loadChannelLogo(channel);
        showOverlayForChannelStart();
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
        networkExecutor.submit(() -> {
            try {
                android.graphics.Bitmap bitmap = channelLogoCache.load(logoUri);
                mainHandler.post(() -> {
                    if (expectedIndex != channelIndex || isFinishing()) return;
                    channelLogo.setImageBitmap(bitmap);
                    channelLogo.setVisibility(View.VISIBLE);
                    channelLogoFallback.setVisibility(View.GONE);
                });
            } catch (Exception ignored) {
                // El monograma del canal permanece visible como respaldo.
            }
        });
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
                showExitDialog();
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
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_INFO) {
                    if (loadFailed) {
                        refreshPlaylist(getPlaylistUrl());
                    } else {
                        showOverlay(false);
                    }
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::showExitDialog);
        }
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
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "Error desconocido." : message;
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
        if (player != null) player.play();
    }

    @Override
    protected void onPause() {
        if (appUpdater != null) appUpdater.onHostPause();
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        playlistGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        if (appUpdater != null) appUpdater.destroy();
        networkExecutor.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
