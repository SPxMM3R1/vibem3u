package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
