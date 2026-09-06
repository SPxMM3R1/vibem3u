package cl.streambox.tv;

import java.util.concurrent.TimeUnit;

/** Bounded recovery per outage; sustained playback starts a new recovery episode. */
final class PlaybackRecoveryEpisode {
    static final long STABLE_PLAYBACK_MS = 15_000L;
    static final int MAX_SAME_SOURCE_RECOVERIES = 2;
    static final int MAX_AUTOMATIC_SOURCE_RELOADS = 2;
    private boolean refreshUsed;
    private boolean fallbackUsed;
    private int sameSourceRecoveries;
    private int sourceReloads;
    private long playingSinceNanos = -1L;
    private boolean stableReported;

    void reset() {
        refreshUsed = false;
        fallbackUsed = false;
        sameSourceRecoveries = 0;
        sourceReloads = 0;
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

    /**
     * Allows a stalled live item to be reopened with the same source before
     * the resolver is asked for a new token or URL.
     */
    boolean trySameSourceRecovery() {
        if (sameSourceRecoveries >= MAX_SAME_SOURCE_RECOVERIES) return false;
        sameSourceRecoveries++;
        return true;
    }

    /** Allows bounded reopening when a direct live URL loses continuity. */
    boolean trySourceReload() {
        if (sourceReloads >= MAX_AUTOMATIC_SOURCE_RELOADS) return false;
        sourceReloads++;
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
        sameSourceRecoveries = 0;
        sourceReloads = 0;
        return true;
    }
}
