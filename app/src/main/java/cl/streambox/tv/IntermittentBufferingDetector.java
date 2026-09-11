package cl.streambox.tv;

import androidx.media3.common.Player;

import java.util.ArrayDeque;
import java.util.Deque;

/** Detects repeated real buffering transitions after a channel has rendered a frame. */
final class IntermittentBufferingDetector {
    static final long WINDOW_MS = 5_000L;
    static final long STABLE_RESET_MS = 4_000L;
    static final int REQUIRED_CYCLES = 5;

    private final Deque<Long> cycleTimesMs = new ArrayDeque<>();
    private boolean hasStarted;
    private boolean wasPlaying;
    private boolean bufferingAfterPlayback;
    private long lastInstabilityMs = -1L;

    void reset() {
        cycleTimesMs.clear();
        hasStarted = false;
        wasPlaying = false;
        bufferingAfterPlayback = false;
        lastInstabilityMs = -1L;
    }

    /**
     * Records only READY -> BUFFERING -> READY transitions that occurred after a real frame.
     * Repeated callbacks while already BUFFERING do not create extra cycles.
     */
    void onPlaybackStateChanged(int state, boolean playRequested, boolean renderedFrame,
            long nowMs) {
        if (renderedFrame) hasStarted = true;

        if (!playRequested) {
            // A user pause must not leave a half-completed cycle waiting for a later resume.
            wasPlaying = false;
            bufferingAfterPlayback = false;
            cycleTimesMs.clear();
            lastInstabilityMs = -1L;
            return;
        }

        if (state == Player.STATE_BUFFERING) {
            if (hasStarted && wasPlaying) bufferingAfterPlayback = true;
            return;
        }

        if (state == Player.STATE_READY) {
            if (hasStarted && bufferingAfterPlayback) {
                cycleTimesMs.addLast(nowMs);
                lastInstabilityMs = nowMs;
                trim(nowMs);
            }
            bufferingAfterPlayback = false;
            wasPlaying = true;
            return;
        }

        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            wasPlaying = false;
            bufferingAfterPlayback = false;
        }
    }

    /** Clears a pending intermittent episode after a genuine user discontinuity/search. */
    void resetForDiscontinuity() {
        cycleTimesMs.clear();
        wasPlaying = false;
        bufferingAfterPlayback = false;
        lastInstabilityMs = -1L;
    }

    /**
     * Returns true only when five cycles are inside the rolling five-second window and the
     * playback progress sampler confirms that media has practically stopped.
     */
    boolean shouldRecover(long nowMs, boolean noRealProgress) {
        trim(nowMs);
        return noRealProgress && cycleTimesMs.size() >= REQUIRED_CYCLES;
    }

    void onStablePlayback(long nowMs, boolean playing) {
        trim(nowMs);
        if (playing && lastInstabilityMs >= 0L
                && nowMs - lastInstabilityMs >= STABLE_RESET_MS) {
            cycleTimesMs.clear();
            lastInstabilityMs = -1L;
        }
    }

    int cycleCount(long nowMs) {
        trim(nowMs);
        return cycleTimesMs.size();
    }

    private void trim(long nowMs) {
        while (!cycleTimesMs.isEmpty()
                && nowMs - cycleTimesMs.peekFirst() > WINDOW_MS) {
            cycleTimesMs.removeFirst();
        }
    }
}
