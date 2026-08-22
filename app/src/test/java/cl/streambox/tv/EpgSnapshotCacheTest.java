package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EpgSnapshotCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reloadsParsedGuideOnlyForTheExactXmlBody() throws Exception {
        File directory = temporaryFolder.newFolder("epg-snapshots");
        String url = "https://example.test/epg.xml";
        byte[] xml = "<tv>version-1</tv>".getBytes(StandardCharsets.UTF_8);
        EpgData data = new EpgData(List.of(
                new EpgProgramme("0104", "Noticias", 1000L, 2000L),
                new EpgProgramme("0104", "Siguiente", 2000L, 3000L)
        ));

        EpgSnapshotCache first = new EpgSnapshotCache(directory);
        first.store(url, xml, data);

        EpgSnapshotCache second = new EpgSnapshotCache(directory);
        EpgData restored = second.load(url, xml);

        assertNotNull(restored);
        assertEquals(2, restored.getProgrammeCount());
        assertEquals("Noticias", restored.findCurrent("0104", 1500L).getTitle());
        assertNull(second.load(
                url,
                "<tv>version-2</tv>".getBytes(StandardCharsets.UTF_8)
        ));
    }
}
