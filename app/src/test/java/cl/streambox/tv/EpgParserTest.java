package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class EpgParserTest {
    @Test
    public void parsesXmlTvAndFindsCurrentProgramme() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tv><channel id=\"0104\"><display-name>TVN</display-name></channel>"
                + "<programme channel=\"0104\" start=\"20260719180000 -0400\" stop=\"20260719190000 -0400\">"
                + "<title lang=\"es\">Noticias Central</title></programme>"
                + "<programme channel=\"0104\" start=\"20260719190000 -0400\" stop=\"20260719200000 -0400\">"
                + "<title lang=\"es\">Programa siguiente</title></programme></tv>";

        EpgData data = EpgParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        long currentTime = EpgParser.parseXmlTvTime("20260719183000 -0400");

        assertEquals(2, data.getProgrammeCount());
        EpgProgramme current = data.findCurrent("0104", currentTime);
        assertNotNull(current);
        assertEquals("Noticias Central", current.getTitle());
        EpgProgramme next = data.findNext("0104", currentTime);
        assertNotNull(next);
        assertEquals("Programa siguiente", next.getTitle());
        assertNull(data.findCurrent("canal-inexistente", currentTime));
    }

    @Test
    public void acceptsTimezoneWithColon() {
        assertEquals(
                EpgParser.parseXmlTvTime("20260719180000 -0400"),
                EpgParser.parseXmlTvTime("20260719180000 -04:00"));
    }
}
