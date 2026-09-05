package cl.streambox.tv;

import java.util.concurrent.TimeUnit;

/** Bounded recovery per outage; sustained playback starts a new recovery episode. */
final class PlaybackRecoveryEpisode {
    static final long STABLE_PLAYBACK_MS = 15_000L;
    private boolean refreshUsed;
    private boolean fallbackUsed;
    private long playingSinceNanos = -1L;
    private boolean stableReported;

    void reset() {
        refreshUsed = false;
        fallbackUsed = false;
        playingSinceNanos = -1L;
        stableReported = false;
    }

    boolean onPlayingChanged(boolean playing, long nowNanos) {
        boolean recovered = settleStablePlayback(nowNanos);
        if (playing) {
            if (playingSinceNanos < 0L) playingSinceNanos = nowNanos;
        } else {
            playingSinceNanos = -1L;
            stableReported = false;
        }
        return recovered;
    }

    boolean tryRefresh() {
        if (refreshUsed) return false;
        refreshUsed = true;
        return true;
    }

    boolean tryFallback() {
        if (fallbackUsed) return false;
        fallbackUsed = true;
        return true;
    }

    void resolutionFailed() {
        refreshUsed = true;
    }

    private boolean settleStablePlayback(long nowNanos) {
        if (stableReported || playingSinceNanos < 0L
                || nowNanos - playingSinceNanos < TimeUnit.MILLISECONDS.toNanos(STABLE_PLAYBACK_MS)) {
            return false;
        }
        stableReported = true;
        refreshUsed = false;
        fallbackUsed = false;
        return true;
    }
}
