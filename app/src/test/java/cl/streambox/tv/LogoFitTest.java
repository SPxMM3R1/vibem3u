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
    public void uhdLogosKeepTheHeightOfTheirNormalVersion() {
        // Sky Sports F1 recortado (846×353) y su UHD (la caja agrega ~30 % de ancho).
        float[] normal = LogoFit.opticalSize(846, 353, AREA, 105f, 32f);
        float[] uhd = LogoFit.opticalSize(1098, 353, AREA, 105f, 32f, LogoFit.UHD_WIDTH_FACTOR);

        assertEquals(normal[1], uhd[1], 0.3f);
        assertTrue(uhd[0] > normal[0] * 1.25f);
    }

    @Test
    public void onlyUhdFileNamesGetTheFamilyFactor() {
        assertEquals(LogoFit.UHD_WIDTH_FACTOR, LogoFit.familyWidthFactor(java.net.URI.create(
                "https://raw.githubusercontent.com/x/lista-m3u/main/logos/sky-sports-f1-uhd.png")), 0f);
        assertEquals(1f, LogoFit.familyWidthFactor(java.net.URI.create(
                "https://raw.githubusercontent.com/x/lista-m3u/main/logos/sky-sports-f1.png")), 0f);
        assertEquals(1f, LogoFit.familyWidthFactor(null), 0f);
    }

    @Test
    public void exactFamiliesAreRecognisedByFileName() {
        String base = "https://raw.githubusercontent.com/x/lista-m3u/main/logos/";
        assertEquals("espn", LogoFit.exactFamily(java.net.URI.create(base + "espn.svg")));
        assertEquals("espn", LogoFit.exactFamily(java.net.URI.create(base + "espn-7.png")));
        assertEquals("tnt-sports", LogoFit.exactFamily(java.net.URI.create(base + "tnt-sports-2.png")));
        assertEquals("eurosport", LogoFit.exactFamily(java.net.URI.create(base + "eurosport-1.png")));
        assertNull(LogoFit.exactFamily(java.net.URI.create(base + "catalogue/espn-6-lam.png")));
        assertNull(LogoFit.exactFamily(java.net.URI.create(base + "sky-sports-f1.png")));
        assertNull(LogoFit.exactFamily(null));
    }

    @Test
    public void familyBoundsKeepTheFaintCanvasCorners() {
        int width = 10;
        int height = 4;
        int[] pixels = new int[width * height];
        pixels[0] = 0x0CFFFFFF;                       // esquina casi transparente
        pixels[width * height - 1] = 0x0CFFFFFF;
        pixels[width + 2] = 0xFFFFFFFF;               // marca
        assertArrayEquals(new int[] {2, 1, 3, 2}, LogoFit.opaqueBounds(pixels, width, height));
        assertArrayEquals(new int[] {0, 0, width, height},
                LogoFit.opaqueBounds(pixels, width, height, 0));
    }

    @Test
    public void familyInkIsMeasuredOnTheSharedBrandOnly() {
        int width = 8;
        int height = 1;
        int[] brandOnly = new int[width];
        int[] brandAndNumber = new int[width];
        for (int x = 0; x < 6; x++) {
            brandOnly[x] = 0xFF000000;
            brandAndNumber[x] = 0xFF000000;
        }
        brandAndNumber[7] = 0xFF000000;               // el número solo cambia la parte derecha
        assertEquals(LogoFit.inkCoverage(brandOnly, width, height, 6),
                LogoFit.inkCoverage(brandAndNumber, width, height, 6), 0f);
        assertTrue(LogoFit.inkCoverage(brandAndNumber, width, height)
                > LogoFit.inkCoverage(brandOnly, width, height));
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
    @Test
    public void autoScaleMovesHalfwayTowardsTheMedianInk() {
        assertEquals(1f, LogoFit.autoScale(LogoFit.INK_MEDIAN_FRACTION), 0.001f);
        // Un logo de trazos finos crece y uno sólido se achica, dentro de los topes.
        assertEquals(LogoFit.AUTO_SCALE_MAX, LogoFit.autoScale(0.05f), 0.001f);
        assertEquals((float) Math.pow(LogoFit.INK_MEDIAN_FRACTION, 0.25), LogoFit.autoScale(1f), 0.001f);
        assertTrue(LogoFit.autoScale(1f) >= LogoFit.AUTO_SCALE_MIN);
        assertTrue(LogoFit.autoScale(0.30f) > 1f);
        assertTrue(LogoFit.autoScale(0.60f) < 1f);
    }

    @Test
    public void inkCoverageAveragesAlpha() {
        int[] half = {0xFF000000, 0x00000000, 0xFF000000, 0x00000000};
        assertEquals(0.5f, LogoFit.inkCoverage(half, 2, 2), 0.001f);
        assertEquals(-1f, LogoFit.inkCoverage(null, 2, 2), 0f);
    }

    @Test
    public void scaledSizeKeepsAspectAndScalesLimitsTogether() {
        float[] base = LogoFit.scaledSize(400, 100, AREA, 105f, 32f, 1f, -1f, 1f);
        float[] bigger = LogoFit.scaledSize(400, 100, AREA, 105f, 32f, 1f, -1f, 1.2f);
        assertEquals(base[0] * 1.2f, bigger[0], 0.01f);
        assertEquals(base[1] * 1.2f, bigger[1], 0.01f);
        // Con tinta en la mediana no cambia nada.
        float[] median = LogoFit.scaledSize(400, 100, AREA, 105f, 32f, 1f,
                LogoFit.INK_MEDIAN_FRACTION, 1f);
        assertArrayEquals(base, median, 0.01f);
    }

    @Test
    public void userScaleIsClampedAndOneRemovesIt() {
        LogoFit.setUserScale("https://x/logo.png", 9f);
        assertEquals(LogoFit.USER_SCALE_MAX, LogoFit.userScale("https://x/logo.png"), 0f);
        LogoFit.setUserScale("https://x/logo.png", 1f);
        assertEquals(1f, LogoFit.userScale("https://x/logo.png"), 0f);
        assertEquals(1f, LogoFit.userScale(null), 0f);
    }
}
