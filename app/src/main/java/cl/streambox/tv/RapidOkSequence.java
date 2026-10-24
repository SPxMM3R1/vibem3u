package cl.streambox.tv;

/** Detects three deliberate, separate OK presses within a short interval. */
final class RapidOkSequence {
    static final long MAX_GAP_MILLIS = 700L;
    private static final int REQUIRED_PRESSES = 3;

    private int pressCount;
    private long lastPressAtMillis = -1L;

    /** Returns true exactly on the third press of a rapid sequence. */
    boolean registerPress(long elapsedRealtimeMillis) {
        if (lastPressAtMillis < 0L || elapsedRealtimeMillis < lastPressAtMillis
                || elapsedRealtimeMillis - lastPressAtMillis > MAX_GAP_MILLIS) {
            pressCount = 1;
        } else {
            pressCount++;
        }
        lastPressAtMillis = elapsedRealtimeMillis;
        if (pressCount < REQUIRED_PRESSES) return false;
        reset();
        return true;
    }

    void reset() {
        pressCount = 0;
        lastPressAtMillis = -1L;
    }
}
