package cl.streambox.tv;

import java.util.List;

/**
 * Navigation state of the full guide, independent of Android views.
 *
 * <p>The guide shows a window of {@link #WINDOW_MILLIS} starting at a half hour. Focus is a
 * row (channel) plus a point in time; the focused programme is the one that contains that
 * time. Moving left/right jumps between programmes of the focused row and slides the window in
 * half-hour steps, never earlier than the half hour that contains "now".
 */
final class EpgGuideNavigator {
    static final long SLOT_MILLIS = 30L * 60L * 1000L;
    static final long WINDOW_MILLIS = 5L * SLOT_MILLIS;
    /** Maximum distance into the future the guide may scroll (the runner publishes up to a week). */
    static final long MAX_AHEAD_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private int rowCount;
    private int row;
    private long minWindowStart;
    private long windowStart;
    private long focusMillis;

    EpgGuideNavigator(int rowCount, int initialRow, long nowMillis) {
        reset(rowCount, initialRow, nowMillis);
    }

    void reset(int rowCount, int initialRow, long nowMillis) {
        this.rowCount = Math.max(0, rowCount);
        this.row = this.rowCount == 0 ? 0 : clamp(initialRow, 0, this.rowCount - 1);
        this.minWindowStart = floorToSlot(nowMillis);
        this.windowStart = minWindowStart;
        this.focusMillis = Math.max(nowMillis, minWindowStart);
    }

    int getRow() { return row; }
    int getRowCount() { return rowCount; }
    long getWindowStart() { return windowStart; }
    long getWindowEnd() { return windowStart + WINDOW_MILLIS; }
    long getFocusMillis() { return focusMillis; }

    /** Moves the focused row; returns true when it changed. */
    boolean moveRow(int delta) {
        if (rowCount == 0) return false;
        int next = clamp(row + delta, 0, rowCount - 1);
        if (next == row) return false;
        row = next;
        return true;
    }

    /**
     * Moves focus to the next ({@code direction > 0}) or previous programme of the focused
     * row. Without guide data the focus moves one half hour. Returns true when focus changed.
     */
    boolean moveTime(int direction, List<EpgProgramme> rowProgrammes) {
        if (direction == 0) return false;
        long target;
        EpgProgramme current = programmeAt(rowProgrammes, focusMillis);
        if (direction > 0) {
            EpgProgramme next = firstStartingAtOrAfter(rowProgrammes,
                    current == null ? focusMillis + 1 : current.getStopMillis());
            target = next != null ? next.getStartMillis()
                    : floorToSlot(focusMillis) + SLOT_MILLIS;
        } else {
            long boundary = current == null ? focusMillis : current.getStartMillis();
            EpgProgramme previous = lastEndingAtOrBefore(rowProgrammes, boundary);
            target = previous != null ? Math.max(previous.getStartMillis(), minWindowStart)
                    : floorToSlot(focusMillis - 1) ;
        }
        target = clamp(target, minWindowStart, minWindowStart + MAX_AHEAD_MILLIS);
        if (target == focusMillis) return false;
        focusMillis = target;
        keepFocusInWindow();
        return true;
    }

    /** Focused programme of the focused row, or null when the guide has none at that time. */
    EpgProgramme focusedProgramme(List<EpgProgramme> rowProgrammes) {
        return programmeAt(rowProgrammes, focusMillis);
    }

    private void keepFocusInWindow() {
        while (focusMillis >= windowStart + WINDOW_MILLIS - SLOT_MILLIS / 2) {
            windowStart += SLOT_MILLIS;
        }
        while (focusMillis < windowStart && windowStart > minWindowStart) {
            windowStart -= SLOT_MILLIS;
        }
        if (windowStart < minWindowStart) windowStart = minWindowStart;
    }

    static EpgProgramme programmeAt(List<EpgProgramme> programmes, long millis) {
        if (programmes == null) return null;
        for (EpgProgramme programme : programmes) {
            if (programme.getStartMillis() <= millis && millis < programme.getStopMillis()) {
                return programme;
            }
        }
        return null;
    }

    private static EpgProgramme firstStartingAtOrAfter(List<EpgProgramme> programmes, long millis) {
        if (programmes == null) return null;
        for (EpgProgramme programme : programmes) {
            if (programme.getStartMillis() >= millis) return programme;
        }
        return null;
    }

    private static EpgProgramme lastEndingAtOrBefore(List<EpgProgramme> programmes, long millis) {
        if (programmes == null) return null;
        EpgProgramme result = null;
        for (EpgProgramme programme : programmes) {
            if (programme.getStopMillis() <= millis) result = programme;
        }
        return result;
    }

    static long floorToSlot(long millis) {
        return Math.floorDiv(millis, SLOT_MILLIS) * SLOT_MILLIS;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
