package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** «1080i» para señales entrelazadas (0.6.55). */
public final class InterlacedLabelTest {
    @Test
    public void labelUsesIForInterlaced() {
        assertEquals("1080i", MainSelector.scanLabel(1080, true));
        assertEquals("720p", MainSelector.scanLabel(720, false));
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
