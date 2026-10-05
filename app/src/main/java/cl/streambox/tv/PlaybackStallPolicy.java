package cl.streambox.tv;

/** Decides when post-start buffering has lasted long enough to reopen playback. */
final class PlaybackStallPolicy {
    static final int CNC_START_BUFFER_MS = 8_000;
    static final int CNC_REBUFFER_MS = 8_000;
    static final long CNC_LIVE_OFFSET_MS = 30_000L;
    private static final long CNC_BUFFERING_QUIET_MS = 25_000L;
    private static final long CNC_RENDER_QUIET_MS = 15_000L;
    private static final long CNC_MAX_STALL_MS = 45_000L;

    private PlaybackStallPolicy() {}

    /** Completed media loads mean rebuffering can still recover; never wait unboundedly. */
    static boolean shouldRecover(boolean cncVerse, boolean buffering, long stalledSinceMs,
            long nowMs, long lastMediaLoadAgeMs, long defaultTimeoutMs) {
        if (!cncVerse) return isExpired(stalledSinceMs, nowMs, defaultTimeoutMs);
        long quietMs = buffering ? CNC_BUFFERING_QUIET_MS : CNC_RENDER_QUIET_MS;
        if (!isExpired(stalledSinceMs, nowMs, quietMs)) return false;
        return isExpired(stalledSinceMs, nowMs, CNC_MAX_STALL_MS)
                || lastMediaLoadAgeMs < 0L || lastMediaLoadAgeMs >= quietMs;
    }

    static String classify(boolean buffering, boolean mediaStopped, boolean audioUnderrun,
            long bufferedMs) {
        if (buffering) return "carga detenida";
        if (mediaStopped && bufferedMs <= 500L) return "audio y vídeo detenidos";
        if (audioUnderrun && bufferedMs <= 500L) return "audio detenido";
        return "decoder detenido";
    }

    static boolean isExpired(long bufferingSinceElapsedRealtime, long nowElapsedRealtime,
            long timeoutMs) {
        return bufferingSinceElapsedRealtime >= 0L
                && timeoutMs > 0L
                && nowElapsedRealtime >= bufferingSinceElapsedRealtime
                && nowElapsedRealtime - bufferingSinceElapsedRealtime >= timeoutMs;
    }
}
