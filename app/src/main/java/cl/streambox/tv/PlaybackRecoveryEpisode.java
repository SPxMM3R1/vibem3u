package cl.streambox.tv;

import java.util.concurrent.TimeUnit;

/** Tracks stable playback so a new outage receives a fresh recovery budget. */
final class PlaybackRecoveryEpisode {
    static final long STABLE_PLAYBACK_MS = 15_000L;
    private long playingSinceNanos = -1L;
    private boolean stableReported;

    void reset() {
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

    private boolean settleStablePlayback(long nowNanos) {
        if (stableReported || playingSinceNanos < 0L
                || nowNanos - playingSinceNanos < TimeUnit.MILLISECONDS.toNanos(STABLE_PLAYBACK_MS)) {
            return false;
        }
        stableReported = true;
        return true;
    }
}
