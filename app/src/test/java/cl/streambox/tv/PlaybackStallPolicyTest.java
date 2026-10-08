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

    @Test
    public void bufferingIsNotMislabelledAsDecoderFailure() {
        assertEquals("carga detenida", PlaybackStallPolicy.classify(true, false, false, 700L));
        assertEquals("carga detenida", PlaybackStallPolicy.classify(true, true, false, 0L));
        assertEquals("audio y vídeo detenidos", PlaybackStallPolicy.classify(false, true, false, 0L));
        assertEquals("audio detenido", PlaybackStallPolicy.classify(false, false, true, 0L));
        assertEquals("decoder detenido", PlaybackStallPolicy.classify(false, false, false, 10_000L));
    }
}
