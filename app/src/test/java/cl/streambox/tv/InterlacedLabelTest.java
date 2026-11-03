package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Barrido de la señal (0.6.56): «1080p» siempre, 59.9 fps y etiqueta si es entrelazada. */
public final class InterlacedLabelTest {
    @Test
    public void labelUsesIForInterlaced() {
        assertEquals("1080p", MainSelector.scanLabel(1080, true));
        assertEquals("720p", MainSelector.scanLabel(720, false));
        assertEquals("59.9", MainSelector.fpsText(29.97f, true));
        assertEquals("30", MainSelector.fpsText(30f, false));
        assertEquals("50", MainSelector.fpsText(25f, true));
    }

    @Test
    public void backupsJsonCarriesInterlaced() {
        PublishedDirectBackups.update("{\"schema\":1,\"channels\":{\"a\":{\"principal_quality\":"
                + "{\"height\":1080,\"kbps\":4000,\"fps\":29.97,\"ok\":true,\"interlaced\":true},\"backups\":[]}}}");
        PublishedDirectBackups.Quality quality = PublishedDirectBackups.qualityFor("a");
        if (quality != null) assertTrue(quality.interlaced);
        assertFalse(new PublishedDirectBackups.Quality(1080, 1, 30f, true, false).interlaced);
    }
}
