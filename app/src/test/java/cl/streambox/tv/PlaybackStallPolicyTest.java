package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class PlaybackStallPolicyTest {
    @Test
    public void doesNotExpireBeforeTheFiveSecondBoundary() {
        assertFalse(PlaybackStallPolicy.isExpired(1_000L, 5_999L, 5_000L));
        assertTrue(PlaybackStallPolicy.isExpired(1_000L, 6_000L, 5_000L));
    }

    @Test
    public void ignoresUnsetOrBackwardsClocks() {
        assertFalse(PlaybackStallPolicy.isExpired(-1L, 6_000L, 5_000L));
        assertFalse(PlaybackStallPolicy.isExpired(6_000L, 5_999L, 5_000L));
        assertFalse(PlaybackStallPolicy.isExpired(1_000L, 6_000L, 0L));
    }

    @Test public void cncBufferingDoesNotReopenAtFiveSecondsWithMediaArriving() {
        assertFalse(PlaybackStallPolicy.shouldRecover(true, true, 1_000L, 6_000L, 500L, 5_000L));
        assertFalse(PlaybackStallPolicy.shouldRecover(true, true, 1_000L, 31_000L, 500L, 5_000L));
    }

    @Test public void cncWaitsForTheHttpReadTimeoutBeforeNoProgressRecovery() {
        assertFalse(PlaybackStallPolicy.shouldRecover(true, true, 1_000L, 25_999L, 30_000L, 5_000L));
        assertTrue(PlaybackStallPolicy.shouldRecover(true, true, 1_000L, 26_000L, 25_000L, 5_000L));
        assertTrue(PlaybackStallPolicy.shouldRecover(true, true, 1_000L, 26_000L, -1L, 5_000L));
    }

    @Test public void cncCannotWaitForeverForDownloadsWithoutPlayback() {
        assertFalse(PlaybackStallPolicy.shouldRecover(true, true, 1_000L, 45_999L, 0L, 5_000L));
        assertTrue(PlaybackStallPolicy.shouldRecover(true, true, 1_000L, 46_000L, 0L, 5_000L));
        assertTrue(PlaybackStallPolicy.shouldRecover(true, false, 0L, 45_000L, 0L, 5_000L));
    }

    @Test public void cncReadyStateStillRecoversARealRenderingStall() {
        assertFalse(PlaybackStallPolicy.shouldRecover(true, false, 0L, 14_999L, -1L, 5_000L));
        assertTrue(PlaybackStallPolicy.shouldRecover(true, false, 0L, 15_000L, 15_000L, 5_000L));
        assertFalse(PlaybackStallPolicy.shouldRecover(true, false, 0L, 15_000L, 0L, 5_000L));
    }

    @Test public void otherProvidersKeepTheirExistingRecoveryThreshold() {
        assertFalse(PlaybackStallPolicy.shouldRecover(false, true, 0L, 4_999L, 0L, 5_000L));
        assertTrue(PlaybackStallPolicy.shouldRecover(false, true, 0L, 5_000L, 0L, 5_000L));
        assertTrue(PlaybackStallPolicy.shouldRecover(false, false, 0L, 5_000L, 0L, 5_000L));
    }

    @Test public void cncPolicyIgnoresUnsetAndBackwardsStallClocks() {
        assertFalse(PlaybackStallPolicy.shouldRecover(true, true, -1L, 90_000L, -1L, 5_000L));
        assertFalse(PlaybackStallPolicy.shouldRecover(true, true, 50_000L, 49_999L, -1L, 5_000L));
    }

    @Test public void bufferingIsNotMislabelledAsDecoderFailure() {
        assertEquals("carga detenida", PlaybackStallPolicy.classify(true, false, false, 700L));
        assertEquals("carga detenida", PlaybackStallPolicy.classify(true, true, false, 0L));
        assertEquals("audio y vídeo detenidos", PlaybackStallPolicy.classify(false, true, false, 0L));
        assertEquals("audio detenido", PlaybackStallPolicy.classify(false, false, true, 0L));
        assertEquals("decoder detenido", PlaybackStallPolicy.classify(false, false, false, 10_000L));
    }

    @Test public void cncStartupAndResumeHaveRunwayWithoutChangingMemoryBudget() {
        assertEquals(8_000, PlaybackStallPolicy.CNC_START_BUFFER_MS);
        assertEquals(8_000, PlaybackStallPolicy.CNC_REBUFFER_MS);
        assertTrue(PlaybackStallPolicy.CNC_LIVE_OFFSET_MS > PlaybackStallPolicy.CNC_REBUFFER_MS);
    }
}
