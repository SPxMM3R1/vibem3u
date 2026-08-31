package cl.streambox.tv;

/** Time-based motion: skipped drawing deadlines never slow the title or accumulate callbacks. */
final class MarqueeMotion {
    private MarqueeMotion() {}

    static float offset(long elapsedNs, int cycleWidthPx, float speedPxPerSecond) {
        if (elapsedNs <= 0L || cycleWidthPx <= 0
                || !Float.isFinite(speedPxPerSecond) || speedPxPerSecond <= 0f) return 0f;
        return (float) ((elapsedNs / 1_000_000_000d * speedPxPerSecond) % cycleWidthPx);
    }
}
