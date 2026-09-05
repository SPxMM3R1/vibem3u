package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.Test;

public final class EpgDataTest {
    @Test
    public void immutableSnapshotSortsProgramsBeforeIndexing() {
        List<EpgProgramme> source = new ArrayList<>(Arrays.asList(
                new EpgProgramme("b", "B tarde", 2_000L, 3_000L),
                new EpgProgramme("a", "A tarde", 2_000L, 3_000L),
                new EpgProgramme("a", "A ahora", 1_000L, 2_000L)
        ));

        EpgData snapshot = new EpgData(source);
        source.clear();

        assertEquals(3, snapshot.getProgrammeCount());
        assertEquals("A ahora", snapshot.findCurrent("a", 1_500L).getTitle());
        assertEquals("A tarde", snapshot.findNext("a", 1_500L).getTitle());
    }

    @Test
    public void mergingOneSnapshotAvoidsASecondCopy() {
        EpgData original = new EpgData(List.of(
                new EpgProgramme("a", "A", 1_000L, 2_000L)
        ));

        assertSame(original, EpgData.merge(List.of(original)));

        LinkedHashMap<String, EpgData> byUrl = new LinkedHashMap<>();
        byUrl.put("https://guide.example/a.xml", original);
        String firstSignature = EpgData.mergeSignature(byUrl);
        byUrl.put("https://guide.example/a.xml", original);
        assertEquals(firstSignature, EpgData.mergeSignature(byUrl));
    }
}
