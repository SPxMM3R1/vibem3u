package cl.streambox.tv;

/**
 * Shared width for the channel OSD, the mini EPG and the settings replacement panel.
 *
 * <p>The OSD spans the window minus {@code channel_overlay_horizontal_margin} (32dp) on each
 * side, as it did up to v0.5.13; the clock uses the same end margin so both right edges line
 * up exactly.
 */
final class OverlayPanelWidth {
    /** Must match {@code @dimen/channel_overlay_horizontal_margin}. */
    static final float EDGE_MARGIN_DP = 32f;

    private OverlayPanelWidth() {}

    static int resolveWidthPx(int availableWidthPx, float density) {
        if (availableWidthPx <= 0) return 0;
        if (!(density > 0f) || Float.isInfinite(density) || Float.isNaN(density)) {
            density = 1f;
        }
        int marginPx = Math.round(EDGE_MARGIN_DP * density);
        return Math.max(0, availableWidthPx - marginPx * 2);
    }
}
