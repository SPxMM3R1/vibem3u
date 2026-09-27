package cl.streambox.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OverlayPanelWidthTest {
    @Test
    public void spansTheWindowMinusTheOsdMarginOnEachSide() {
        assertEquals(1_856, OverlayPanelWidth.resolveWidthPx(1_920, 1f));
        assertEquals(1_792, OverlayPanelWidth.resolveWidthPx(1_920, 2f));
    }

    @Test
    public void scalesTheMarginWithDensity() {
        assertEquals(1_184, OverlayPanelWidth.resolveWidthPx(1_280, 1.5f));
    }

    @Test
    public void keepsMarginsOnNarrowWindows() {
        assertEquals(296, OverlayPanelWidth.resolveWidthPx(360, 1f));
        assertEquals(0, OverlayPanelWidth.resolveWidthPx(40, 1f));
    }

    @Test
    public void invalidInputsFailSafely() {
        assertEquals(0, OverlayPanelWidth.resolveWidthPx(0, 1f));
        assertEquals(336, OverlayPanelWidth.resolveWidthPx(400, 0f));
    }
}
