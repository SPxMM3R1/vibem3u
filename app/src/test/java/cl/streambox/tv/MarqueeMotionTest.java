package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MarqueeMotionTest {
    @Test public void advancesByElapsedTimeNotByTheNumberOfDrawnFrames() {
        assertEquals(40f, MarqueeMotion.offset(1_000_000_000L, 212, 40f), 0.001f);
        assertEquals(60f, MarqueeMotion.offset(1_500_000_000L, 212, 40f), 0.001f);
    }

    @Test public void repeatsWithTheSameSmallGapWithoutAnEmptyCycle() {
        assertEquals(0f, MarqueeMotion.offset(5_300_000_000L, 212, 40f), 0.001f);
        assertEquals(4f, MarqueeMotion.offset(5_400_000_000L, 212, 40f), 0.001f);
    }

    @Test public void staysBoundedAfterLongPlayback() {
        float offset = MarqueeMotion.offset(86_400_000_000_000L, 413, 60f);
        assertTrue(offset >= 0f && offset < 413f);
    }

    @Test public void rejectsInvalidGeometryAndTiming() {
        assertEquals(0f, MarqueeMotion.offset(-1L, 212, 40f), 0f);
        assertEquals(0f, MarqueeMotion.offset(1_000L, 0, 40f), 0f);
        assertEquals(0f, MarqueeMotion.offset(1_000L, 212, Float.NaN), 0f);
    }
}
