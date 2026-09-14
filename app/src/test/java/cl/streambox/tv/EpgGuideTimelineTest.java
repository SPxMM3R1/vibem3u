package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EpgGuideTimelineTest {
    @Test public void distantTargetsStayInsideViewportInBothDirections() {
        for (long target : new long[]{-50_000_000L, 0L, 10_800_000L, 80_000_000L}) {
            long start = EpgGuideTimeline.visibleWindow(0L, target);
            assertTrue(target >= start);
            assertTrue(target < start + EpgGuideTimeline.GUIDE_WINDOW_MS);
        }
    }

    @Test public void shortProgramsAndGapsAreNotSkipped() {
        java.util.List<EpgProgramme> items = java.util.Arrays.asList(
                new EpgProgramme("x", "Corto", 0L, 10_000L),
                new EpgProgramme("x", "Siguiente", 10_000L, 20_000L),
                new EpgProgramme("x", "Tras hueco", 30_000L, 40_000L));
        assertEquals(10_000L, EpgGuideTimeline.adjacentTime(items, 1L, 1));
        assertEquals(9_999L, EpgGuideTimeline.adjacentTime(items, 10_000L, -1));
        assertEquals(30_000L, EpgGuideTimeline.adjacentTime(items, 20_000L, 1));
        assertEquals(19_999L, EpgGuideTimeline.adjacentTime(items, 25_000L, -1));
    }

    @Test public void calendarDayPreservesHourAcrossDst() {
        java.util.TimeZone zone = java.util.TimeZone.getTimeZone("America/New_York");
        long anchor = EpgParser.parseXmlTvTime("20260307120000 -0500");
        assertEquals(EpgParser.parseXmlTvTime("20260308120000 -0400"),
                EpgGuideTimeline.dayOffset(anchor, 1, zone));
    }
    @Test
    public void floorsAcrossMidnightWithoutUsingLocalCalendarState() {
        long beforeMidnight = EpgParser.parseXmlTvTime("20260714235900 -0400");
        long expected = EpgParser.parseXmlTvTime("20260714233000 -0400");
        assertEquals(expected, EpgGuideTimeline.floorHalfHour(beforeMidnight));
    }

    @Test
    public void positionsProgramsProportionallyAndAllowsGaps() {
        long start = 0L;
        assertEquals(.5f, EpgGuideTimeline.position(
                90L * 60L * 1000L,
                start,
                EpgGuideTimeline.GUIDE_WINDOW_MS
        ), .0001f);

        EpgProgramme gap = new EpgProgramme("x", "Después", "", 4L, 5L);
        assertFalse(EpgGuideTimeline.intersects(gap, 0L, 4L));
        assertTrue(EpgGuideTimeline.intersects(gap, 3L, 6L));
    }

    @Test
    public void programmeCrossingMidnightRemainsInTheWindow() {
        long start = EpgParser.parseXmlTvTime("20260714235900 -0400");
        EpgProgramme programme = new EpgProgramme(
                "x",
                "Noche",
                "",
                start - 10L * 60L * 1000L,
                start + 20L * 60L * 1000L
        );
        assertTrue(EpgGuideTimeline.intersects(
                programme,
                EpgGuideTimeline.floorHalfHour(start),
                EpgGuideTimeline.floorHalfHour(start) + EpgGuideTimeline.GUIDE_WINDOW_MS
        ));
    }
}
