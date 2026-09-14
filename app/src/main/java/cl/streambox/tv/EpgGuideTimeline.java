package cl.streambox.tv;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Pure time calculations used by the guide so midnight and gaps stay testable. */
final class EpgGuideTimeline {
    static final long HALF_HOUR_MS = 30L * 60L * 1000L;
    static final long GUIDE_WINDOW_MS = 3L * 60L * 60L * 1000L;

    private EpgGuideTimeline() {}

    static long visibleWindow(long start, long target) {
        if (target < start) return floorHalfHour(target);
        if (target >= start + GUIDE_WINDOW_MS) {
            return floorHalfHour(target) - GUIDE_WINDOW_MS + HALF_HOUR_MS;
        }
        return start;
    }

    static long adjacentTime(List<EpgProgramme> programmes, long time, int direction) {
        for (EpgProgramme programme : programmes) {
            if (programme.getStartMillis() <= time && time < programme.getStopMillis()) {
                return direction > 0 ? programme.getStopMillis() : programme.getStartMillis() - 1L;
            }
        }
        long target = time + (direction > 0 ? HALF_HOUR_MS : -HALF_HOUR_MS);
        for (EpgProgramme programme : programmes) {
            if (direction > 0 && programme.getStartMillis() > time) {
                target = Math.min(target, programme.getStartMillis());
            } else if (direction < 0 && programme.getStopMillis() <= time) {
                target = Math.max(target, programme.getStopMillis() - 1L);
            }
        }
        return target;
    }

    // Calendar days, not fixed 24-hour offsets: preserves local hour across DST.
    static long dayOffset(long anchor, int days, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(anchor);
        calendar.add(Calendar.DATE, days);
        return calendar.getTimeInMillis();
    }

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
