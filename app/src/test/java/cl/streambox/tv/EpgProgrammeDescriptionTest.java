package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class EpgProgrammeDescriptionTest {
    @Test
    public void normalizedDescriptionIsReusedAsIs() {
        String clean = "Partido de vuelta de la Copa Sudamericana.";
        assertSame(clean, EpgProgramme.normalizeDescription(clean));
    }

    @Test
    public void collapsesWhitespaceAndTrims() {
        assertEquals("a b c", EpgProgramme.normalizeDescription("  a \n\tb   c "));
        assertEquals("a b", EpgProgramme.normalizeDescription("a b".replace(' ', '\n')));
        assertEquals("", EpgProgramme.normalizeDescription(null));
        assertEquals("", EpgProgramme.normalizeDescription(""));
        assertEquals("", EpgProgramme.normalizeDescription("   "));
    }

    @Test
    public void longDescriptionIsCut() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 700; index++) text.append('x');
        String result = EpgProgramme.normalizeDescription(text.toString());
        assertEquals(EpgProgramme.MAX_DESCRIPTION_CHARS, result.length());
    }
}
