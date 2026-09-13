package cl.streambox.tv;

/**
 * Pure thresholds used to decide when playback resource pressure is worth
 * showing to the user. A warning never performs recovery by itself.
 */
final class PlaybackResourceWarningPolicy {
    static final long BUFFER_WARNING_DELAY_MS = 1_500L;
    static final long BUFFER_WARNING_MAX_MS = 750L;
    static final int RUNNING_LOW_MEMORY_LEVEL = 10;
    static final int RUNNING_CRITICAL_MEMORY_LEVEL = 15;
    static final int LOW_MEMORY_CALLBACK_LEVEL = 80;
    static final int HEAP_WARNING_PERCENT = 88;
    static final int HEAP_CRITICAL_PERCENT = 94;
    static final int ALLOCATOR_WARNING_PERCENT = 92;

    enum Type {
        NONE,
        BUFFER,
        MEMORY,
        MEMORY_CRITICAL
    }

    private PlaybackResourceWarningPolicy() {}

    static boolean isBufferWarning(
            boolean playbackStarted,
            boolean userPaused,
            boolean loading,
            long bufferedDurationMs,
            long loadingSinceElapsedRealtime,
            long nowElapsedRealtime
    ) {
        if (!playbackStarted || userPaused || !loading) return false;
        if (bufferedDurationMs < 0L || bufferedDurationMs > BUFFER_WARNING_MAX_MS) {
            return false;
        }
        if (loadingSinceElapsedRealtime < 0L
                || nowElapsedRealtime < loadingSinceElapsedRealtime) {
            return false;
        }
        return nowElapsedRealtime - loadingSinceElapsedRealtime >= BUFFER_WARNING_DELAY_MS;
    }

    static Type memoryType(
            boolean playbackStarted,
            int memoryPressureLevel,
            long heapUsedBytes,
            long heapMaxBytes,
            long allocatedBytes,
            long targetBytes
    ) {
        if (!playbackStarted) return Type.NONE;

        int heapPercent = percentage(heapUsedBytes, heapMaxBytes);
        int allocatorPercent = percentage(allocatedBytes, targetBytes);
        // Do not treat background/UI-hidden trim levels as playback failure.
        // Android uses values such as 20, 40 and 60 for lifecycle/background
        // pressure, while 10 and 15 are the running low/critical signals.
        boolean critical = memoryPressureLevel == RUNNING_CRITICAL_MEMORY_LEVEL
                || memoryPressureLevel >= LOW_MEMORY_CALLBACK_LEVEL
                || heapPercent >= HEAP_CRITICAL_PERCENT;
        if (critical) return Type.MEMORY_CRITICAL;

        boolean warning = memoryPressureLevel == RUNNING_LOW_MEMORY_LEVEL
                || heapPercent >= HEAP_WARNING_PERCENT
                || allocatorPercent >= ALLOCATOR_WARNING_PERCENT;
        return warning ? Type.MEMORY : Type.NONE;
    }

    static int percentage(long value, long maximum) {
        if (value <= 0L || maximum <= 0L) return 0;
        if (value >= maximum) return 100;
        return (int) Math.min(100L, (value * 100L) / maximum);
    }
}
