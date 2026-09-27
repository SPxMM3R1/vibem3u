package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EpgGuideNavigatorTest {
    private static final long MIN = 60_000L;
    // 13:52 relative to a half-hour-aligned origin at 13:00 (T0).
    private static final long T0 = 1_000L * EpgGuideNavigator.SLOT_MILLIS;
    private static final long NOW = T0 + 52 * MIN;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static EpgProgramme programme(String title, long startMinutes, long stopMinutes) {
        return new EpgProgramme("f1", title, T0 + startMinutes * MIN, T0 + stopMinutes * MIN);
    }

    private final List<EpgProgramme> row = List.of(
            programme("Clasificacion", 0, 90),
            programme("Notebook", 90, 120),
            programme("Classics", 120, 240),
            programme("Noche", 240, 600));

    @Test
    public void startsOnTheCurrentProgrammeAndTheHalfHourContainingNow() {
        EpgGuideNavigator navigator = new EpgGuideNavigator(10, 3, NOW);

        assertEquals(3, navigator.getRow());
        assertEquals(T0 + 30 * MIN, navigator.getWindowStart());
        assertEquals("Clasificacion", navigator.focusedProgramme(row).getTitle());
    }

    @Test
    public void rightAndLeftJumpBetweenProgrammesOfTheRow() {
        EpgGuideNavigator navigator = new EpgGuideNavigator(10, 0, NOW);

        assertTrue(navigator.moveTime(1, row));
        assertEquals("Notebook", navigator.focusedProgramme(row).getTitle());
        assertTrue(navigator.moveTime(1, row));
        assertEquals("Classics", navigator.focusedProgramme(row).getTitle());
        assertTrue(navigator.moveTime(-1, row));
        assertEquals("Notebook", navigator.focusedProgramme(row).getTitle());
        assertTrue(navigator.moveTime(-1, row));
        assertEquals("Clasificacion", navigator.focusedProgramme(row).getTitle());
    }

    @Test
    public void neverScrollsBeforeTheHalfHourContainingNow() {
        EpgGuideNavigator navigator = new EpgGuideNavigator(10, 0, NOW);

        navigator.moveTime(-1, row);
        navigator.moveTime(-1, row);

        assertEquals(T0 + 30 * MIN, navigator.getWindowStart());
        assertTrue(navigator.getFocusMillis() >= navigator.getWindowStart());
        assertEquals("Clasificacion", navigator.focusedProgramme(row).getTitle());
    }

    @Test
    public void windowFollowsFocusIntoTheFuture() {
        EpgGuideNavigator navigator = new EpgGuideNavigator(10, 0, NOW);

        navigator.moveTime(1, row); // 14:30
        navigator.moveTime(1, row); // 15:00
        navigator.moveTime(1, row); // 17:00

        assertEquals("Noche", navigator.focusedProgramme(row).getTitle());
        assertTrue(navigator.getFocusMillis() >= navigator.getWindowStart());
        assertTrue(navigator.getFocusMillis() < navigator.getWindowEnd());
    }

    @Test
    public void emptyRowsMoveByHalfHoursAndRowsAreClamped() {
        EpgGuideNavigator navigator = new EpgGuideNavigator(3, 0, NOW);

        assertTrue(navigator.moveTime(1, List.of()));
        assertEquals(T0 + 60 * MIN, navigator.getFocusMillis());
        assertNull(navigator.focusedProgramme(List.of()));
        assertFalse(navigator.moveRow(-1));
        assertTrue(navigator.moveRow(5));
        assertEquals(2, navigator.getRow());
    }

    @Test
    public void findInWindowReturnsOverlappingProgrammesInOrder() {
        EpgData data = new EpgData(row);

        List<EpgProgramme> window = data.findInWindow("f1", T0 + 60 * MIN, T0 + 150 * MIN);

        assertEquals(3, window.size());
        assertEquals("Clasificacion", window.get(0).getTitle());
        assertEquals("Classics", window.get(2).getTitle());
        assertTrue(data.findInWindow("otro", T0, T0 + 60 * MIN).isEmpty());
    }

    @Test
    public void parserKeepsTheFirstDescriptionAndCacheRestoresIt() throws Exception {
        String xml = "<tv><programme channel=\"0104\" start=\"20260719180000 -0400\""
                + " stop=\"20260719190000 -0400\"><title>Noticias</title>"
                + "<desc lang=\"es\">  Resumen   del dia.\n </desc><desc>Otra</desc></programme></tv>";
        EpgData parsed = EpgParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        long during = EpgParser.parseXmlTvTime("20260719183000 -0400");

        assertEquals("Resumen del dia.", parsed.findCurrent("0104", during).getDescription());

        File directory = temporaryFolder.newFolder("snapshots");
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        new EpgSnapshotCache(directory).store("https://example.test/epg.xml", body, parsed);
        EpgData restored = new EpgSnapshotCache(directory).load("https://example.test/epg.xml", body);

        assertEquals("Resumen del dia.", restored.findCurrent("0104", during).getDescription());
    }

    @Test
    public void longDescriptionsAreTrimmed() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 1_000; index++) text.append('a');

        String description = new EpgProgramme("c", "t", 0L, 1L, text.toString()).getDescription();

        assertEquals(EpgProgramme.MAX_DESCRIPTION_CHARS, description.length());
        assertTrue(description.endsWith("…"));
    }
}
