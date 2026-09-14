package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EpgGuideTimelineTest {
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
