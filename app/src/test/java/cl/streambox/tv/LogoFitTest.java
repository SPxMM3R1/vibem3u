package cl.streambox.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogoFitTest {
    private static final float AREA = 75f * 23f;

    @Test
    public void everyLogoGetsTheSameVisualAreaWithinLimits() {
        float[] wide = LogoFit.opticalSize(400, 100, AREA, 105f, 32f);
        float[] square = LogoFit.opticalSize(200, 200, AREA, 105f, 32f);

        assertEquals(AREA, wide[0] * wide[1], 1f);
        assertEquals(4f, wide[0] / wide[1], 0.01f);
        // Un logo cuadrado llega al tope de alto antes de alcanzar la superficie.
        assertEquals(32f, square[1], 0.01f);
        assertEquals(32f, square[0], 0.01f);
    }

    @Test
    public void veryWideLogosStopAtTheMaximumWidth() {
        float[] size = LogoFit.opticalSize(1000, 50, AREA, 105f, 32f);

        assertEquals(105f, size[0], 0.01f);
        assertEquals(5.25f, size[1], 0.01f);
    }

    @Test
    public void invalidSizesDrawNothing() {
        assertArrayEquals(new float[] {0f, 0f}, LogoFit.opticalSize(0, 10, AREA, 105f, 32f), 0f);
        assertArrayEquals(new float[] {0f, 0f}, LogoFit.opticalSize(10, 10, 0f, 105f, 32f), 0f);
    }

    @Test
    public void opaqueBoundsIgnoreTransparentMargins() {
        int width = 6;
        int height = 4;
        int[] pixels = new int[width * height];
        pixels[1 * width + 2] = 0xFF000000;
        pixels[2 * width + 4] = 0x80FFFFFF;
        pixels[3 * width + 5] = 0x05FFFFFF; // casi transparente: no cuenta

        assertArrayEquals(new int[] {2, 1, 5, 3}, LogoFit.opaqueBounds(pixels, width, height));
    }

    @Test
    public void fullyTransparentOrInvalidImagesHaveNoBounds() {
        assertNull(LogoFit.opaqueBounds(new int[12], 4, 3));
        assertNull(LogoFit.opaqueBounds(new int[2], 4, 3));
        assertNull(LogoFit.opaqueBounds(null, 4, 3));
    }

    @Test
    public void opaqueImageKeepsItsFullBounds() {
        int[] pixels = new int[9];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        int[] bounds = LogoFit.opaqueBounds(pixels, 3, 3);
        assertTrue(bounds != null);
        assertArrayEquals(new int[] {0, 0, 3, 3}, bounds);
    }
}
