package cl.streambox.tv;

/** Shared responsive width for the channel OSD and the settings replacement panel. */
final class OverlayPanelWidth {
    private static final float TARGET_FRACTION = 0.86f;
    private static final float MIN_EDGE_MARGIN_DP = 24f;
    private static final float MIN_PANEL_WIDTH_DP = 320f;
    private static final float MAX_PANEL_WIDTH_DP = 1_440f;

    private OverlayPanelWidth() {}

    static int resolveWidthPx(int availableWidthPx, float density) {
        if (availableWidthPx <= 0) return 0;
        if (!(density > 0f) || Float.isInfinite(density) || Float.isNaN(density)) {
            density = 1f;
        }

        float windowWidthDp = availableWidthPx / density;
        float edgeMarginDp = Math.min(MIN_EDGE_MARGIN_DP, windowWidthDp / 2f);
        float safeMaximumDp = Math.max(0f, windowWidthDp - edgeMarginDp * 2f);
        float minimumWidthDp = Math.min(MIN_PANEL_WIDTH_DP, safeMaximumDp);
        float targetWidthDp = Math.min(MAX_PANEL_WIDTH_DP, windowWidthDp * TARGET_FRACTION);
        float resolvedWidthDp = Math.min(
                safeMaximumDp,
                Math.max(minimumWidthDp, targetWidthDp)
        );
        return Math.min(availableWidthPx, Math.round(resolvedWidthDp * density));
    }
}
