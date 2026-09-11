package cl.streambox.tv;

import androidx.media3.common.C;

/**
 * Conservative detector for renderer-level A/V stalls. It never runs while Media3 is buffering,
 * and it requires independent video-frame, audio and position signals before requesting recovery.
 */
final class PlaybackAvSyncDetector {
    static final long RENDERER_STALL_TIMEOUT_MS = 2_000L;
    static final long PROGRESS_STALL_TIMEOUT_MS = 1_500L;
    static final long POSITION_STEP_MS = 250L;
    static final long UNDERRUN_WINDOW_MS = 2_000L;
    static final int UNDERRUNS_REQUIRED = 2;

    enum Signal {
        NONE,
        VIDEO_STALLED_WITH_AUDIO,
        AUDIO_UNDERRUN_WITH_VIDEO
    }

    private long videoStalledSinceMs = -1L;
    private long positionStalledSinceMs = -1L;
    private long lastPositionMs = C.TIME_UNSET;
    private long lastObservedVideoFrameNs = C.TIME_UNSET;
    private long lastVideoFrameObservedAtMs = -1L;
    private long observedUnderrunCount;
    private long underrunWindowStartMs = -1L;
    private int underrunsInWindow;

    void reset() {
        videoStalledSinceMs = -1L;
        positionStalledSinceMs = -1L;
        lastPositionMs = C.TIME_UNSET;
        lastObservedVideoFrameNs = C.TIME_UNSET;
        lastVideoFrameObservedAtMs = -1L;
        observedUnderrunCount = 0L;
        underrunWindowStartMs = -1L;
        underrunsInWindow = 0;
    }

    /**
     * Samples both renderers. The caller must only pass ready/playing samples; buffering is
     * intentionally handled by the normal loading detector.
     */
    Signal sample(long nowMs, long nowNs, boolean shouldPlay, boolean buffering,
            boolean hasVideo, long lastVideoFrameNs, boolean audioEnabled,
            boolean audioPositionAdvancingObserved, long currentPositionMs,
            long audioUnderrunCount) {
        if (!shouldPlay || buffering) {
            resetProgress(currentPositionMs);
            observedUnderrunCount = Math.max(0L, audioUnderrunCount);
            underrunWindowStartMs = -1L;
            underrunsInWindow = 0;
            return Signal.NONE;
        }

        observeVideoProgress(nowMs, nowNs, hasVideo, lastVideoFrameNs);
        observePositionProgress(nowMs, currentPositionMs);
        observeUnderruns(nowMs, audioUnderrunCount);

        boolean positionAdvancing = positionStalledSinceMs < 0L;
        boolean videoFresh = hasVideo && videoStalledSinceMs < 0L;
        boolean audioLikelyAdvancing = audioEnabled
                && audioPositionAdvancingObserved
                && positionAdvancing;

        if (hasVideo && audioLikelyAdvancing && isVideoStalledFor(nowMs,
                RENDERER_STALL_TIMEOUT_MS)) {
            return Signal.VIDEO_STALLED_WITH_AUDIO;
        }

        if (audioEnabled && videoFresh && underrunsInWindow >= UNDERRUNS_REQUIRED) {
            return Signal.AUDIO_UNDERRUN_WITH_VIDEO;
        }
        return Signal.NONE;
    }

    boolean isRealProgressStalled(long nowMs, boolean hasVideo) {
        boolean positionStalled = positionStalledSinceMs >= 0L
                && nowMs - positionStalledSinceMs >= PROGRESS_STALL_TIMEOUT_MS;
        if (!hasVideo) return positionStalled;
        boolean videoStalled = videoStalledSinceMs >= 0L
                && nowMs - videoStalledSinceMs >= PROGRESS_STALL_TIMEOUT_MS;
        return positionStalled && videoStalled;
    }

    private void observeVideoProgress(long nowMs, long nowNs, boolean hasVideo,
            long lastVideoFrameNs) {
        if (!hasVideo || lastVideoFrameNs == C.TIME_UNSET || lastVideoFrameNs < 0L
                || nowNs < lastVideoFrameNs) {
            videoStalledSinceMs = -1L;
            return;
        }
        if (lastVideoFrameNs != lastObservedVideoFrameNs) {
            lastObservedVideoFrameNs = lastVideoFrameNs;
            lastVideoFrameObservedAtMs = nowMs;
            videoStalledSinceMs = -1L;
        } else if (lastVideoFrameObservedAtMs >= 0L
                && nowMs - lastVideoFrameObservedAtMs >= PROGRESS_STALL_TIMEOUT_MS) {
            videoStalledSinceMs = lastVideoFrameObservedAtMs;
        }
    }

    private void observePositionProgress(long nowMs, long currentPositionMs) {
        if (currentPositionMs == C.TIME_UNSET || currentPositionMs < 0L) return;
        if (lastPositionMs == C.TIME_UNSET || currentPositionMs < lastPositionMs
                || currentPositionMs - lastPositionMs >= POSITION_STEP_MS) {
            positionStalledSinceMs = -1L;
        } else if (positionStalledSinceMs < 0L) {
            positionStalledSinceMs = nowMs;
        }
        lastPositionMs = currentPositionMs;
    }

    private void observeUnderruns(long nowMs, long audioUnderrunCount) {
        if (audioUnderrunCount < observedUnderrunCount) {
            observedUnderrunCount = audioUnderrunCount;
            underrunWindowStartMs = -1L;
            underrunsInWindow = 0;
            return;
        }
        long delta = audioUnderrunCount - observedUnderrunCount;
        observedUnderrunCount = audioUnderrunCount;
        if (delta > 0L) {
            if (underrunWindowStartMs < 0L
                    || nowMs - underrunWindowStartMs > UNDERRUN_WINDOW_MS) {
                underrunWindowStartMs = nowMs;
                underrunsInWindow = 0;
            }
            underrunsInWindow += (int) Math.min(delta, 8L);
        } else if (underrunWindowStartMs >= 0L
                && nowMs - underrunWindowStartMs > UNDERRUN_WINDOW_MS) {
            underrunWindowStartMs = -1L;
            underrunsInWindow = 0;
        }
    }

    private boolean isVideoStalledFor(long nowMs, long timeoutMs) {
        return videoStalledSinceMs >= 0L && nowMs - videoStalledSinceMs >= timeoutMs;
    }

    private void resetProgress(long currentPositionMs) {
        videoStalledSinceMs = -1L;
        positionStalledSinceMs = -1L;
        lastObservedVideoFrameNs = C.TIME_UNSET;
        lastVideoFrameObservedAtMs = -1L;
        if (currentPositionMs != C.TIME_UNSET && currentPositionMs >= 0L) {
            lastPositionMs = currentPositionMs;
        }
    }
}
