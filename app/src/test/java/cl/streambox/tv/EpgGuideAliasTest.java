package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 0.6.27: canales de epg-2.xml que comparten la guía de otro (data-guide-of). */
public class EpgGuideAliasTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String XML = "<?xml version='1.0' encoding='utf-8'?><tv>"
            + "<channel id=\"A\"><display-name>A</display-name></channel>"
            + "<channel id=\"B\" data-guide-of=\"A\"><display-name>B</display-name></channel>"
            + "<programme start=\"20261021200000 +0000\" stop=\"20261021220000 +0000\" channel=\"A\">"
            + "<title>Película</title></programme></tv>";

    private static EpgData parse() throws Exception {
        return EpgParser.parse(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aliasChannelUsesTheGuideOfItsSource() throws Exception {
        EpgData data = parse();
        long now = EpgParser.parseXmlTvTime("20261021210000 +0000");
        assertEquals("Película", data.findCurrent("B", now).getTitle());
        assertTrue(data.hasChannel("B"));
        assertEquals(1, data.getProgrammeCount());
    }

    @Test
    public void aliasesSurviveSnapshotsAndMerges() throws Exception {
        EpgData data = parse();
        EpgSnapshotCache cache = new EpgSnapshotCache(folder.newFolder("snap"));
        String digest = "00000000000000000000000000000000000000000000000000000000000000aa";
        cache.storeDigest("https://x/epg-2.xml", digest, data);
        EpgData loaded = cache.loadDigest("https://x/epg-2.xml", digest);
        assertNotNull(loaded);
        long now = EpgParser.parseXmlTvTime("20261021210000 +0000");
        assertEquals("Película", loaded.findCurrent("B", now).getTitle());
        EpgData other = EpgParser.parse(new ByteArrayInputStream(("<tv><programme start=\"20261021200000 +0000\" "
                + "stop=\"20261021220000 +0000\" channel=\"C\"><title>Otra</title></programme></tv>")
                .getBytes(StandardCharsets.UTF_8)));
        EpgData merged = EpgData.merge(Arrays.asList(other, loaded));
        assertEquals("Película", merged.findCurrent("B", now).getTitle());
        assertEquals("Otra", merged.findCurrent("C", now).getTitle());
    }
}
