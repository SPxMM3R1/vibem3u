package cl.streambox.tv;

/** Pure time calculations used by the guide so midnight and gaps stay testable. */
final class EpgGuideTimeline {
    static final long HALF_HOUR_MS = 30L * 60L * 1000L;
    static final long GUIDE_WINDOW_MS = 3L * 60L * 60L * 1000L;

    private EpgGuideTimeline() {}

    static long floorHalfHour(long timeMillis) {
        return Math.floorDiv(timeMillis, HALF_HOUR_MS) * HALF_HOUR_MS;
    }

    static float position(long timeMillis, long windowStartMillis, long windowDurationMillis) {
        if (windowDurationMillis <= 0L) return 0f;
        return (timeMillis - windowStartMillis) / (float) windowDurationMillis;
    }

    static boolean intersects(
            EpgProgramme programme,
            long windowStartMillis,
            long windowEndMillis
    ) {
        return programme != null
                && programme.getStopMillis() > windowStartMillis
                && programme.getStartMillis() < windowEndMillis;
    }
}
