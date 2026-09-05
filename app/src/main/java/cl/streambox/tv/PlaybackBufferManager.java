package cl.streambox.tv;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import java.util.ArrayDeque;

/** Bounded sample memory and a small, URL-free diagnostic history. Never flushes live samples. */
@UnstableApi
final class PlaybackBufferManager implements AutoCloseable {
    private static final String TAG = "VibeM3U-Buffer";
    private final DefaultAllocator allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final int targetBytes;
    private ExoPlayer player;
    private PlaybackStartupMetrics metrics;
    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            record("sample", 0, 0, false);
            handler.postDelayed(this, 5_000L);
        }
    };
    private final Player.Listener listener = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int state) {
            record("state", 0, 0, state == Player.STATE_BUFFERING);
        }
        @Override public void onPlayerError(PlaybackException error) {
            int http = 0;
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
                if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                    http = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                    break;
                }
            }
            record("error", error.errorCode, http, true);
        }
    };

    PlaybackBufferManager(long maxHeapBytes, boolean lowRamDevice) {
        targetBytes = PlaybackBufferBudget.targetBytes(maxHeapBytes, lowRamDevice);
    }

    DefaultLoadControl loadControl() {
        // Retain Media3's playback and rebuffer duration defaults. The byte target takes
        // priority; it is a loading threshold, not a hard cap on decoder/process memory.
        return new DefaultLoadControl.Builder().setAllocator(allocator)
                .setTargetBufferBytes(targetBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(0, false).build();
    }

    void attach(ExoPlayer player, PlaybackStartupMetrics metrics) {
        this.player = player;
        this.metrics = metrics;
        player.addListener(listener);
        handler.post(sampler);
    }

    void onMemoryPressure(int level) {
        // trim() drops only unused allocations above the allocator's current target.
        // Do not reset/stop/seek or force GC while a channel is playing.
        allocator.trim();
        record("memory_" + level, 0, 0, true);
    }

    private void record(String event, int error, int http, boolean dump) {
        if (player == null) return;
        Runtime runtime = Runtime.getRuntime();
        String sample = "tMs=" + android.os.SystemClock.elapsedRealtime()
                + " attempt=" + (metrics == null ? -1L : metrics.currentId())
                + " event=" + event + " state=" + player.getPlaybackState()
                + " loading=" + player.isLoading() + " bufferedMs=" + player.getTotalBufferedDuration()
                + " allocatedBytes=" + allocator.getTotalBytesAllocated()
                + " targetBytes=" + targetBytes
                + " heapUsed=" + (runtime.totalMemory() - runtime.freeMemory())
                + " heapMax=" + runtime.maxMemory() + " error=" + error + " http=" + http;
        if (history.size() == 24) history.removeFirst();
        history.addLast(sample);
        if (dump) for (String entry : history) Log.i(TAG, entry);
    }

    @Override public void close() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.removeListener(listener);
        player = null;
        metrics = null;
        history.clear();
        // The player's load control resets its allocator when released.
    }
}
