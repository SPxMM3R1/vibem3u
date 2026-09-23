package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OverlayPanelWidthTest {
    @Test
    public void capsWidthOnLargeTelevisionWindows() {
        assertEquals(1_440, OverlayPanelWidth.resolveWidthPx(1_920, 1f));
    }

    @Test
    public void keepsTheSameResponsiveFractionAtCommonTvDensity() {
        assertEquals(1_101, OverlayPanelWidth.resolveWidthPx(1_280, 1.5f));
    }

    @Test
    public void preservesSafeMarginsOnNarrowWindows() {
        int width = OverlayPanelWidth.resolveWidthPx(360, 1f);
        assertEquals(312, width);
        assertTrue(width < 360);
    }

    @Test
    public void invalidInputsFailSafely() {
        assertEquals(0, OverlayPanelWidth.resolveWidthPx(0, 1f));
        assertEquals(344, OverlayPanelWidth.resolveWidthPx(400, 0f));
    }
}
