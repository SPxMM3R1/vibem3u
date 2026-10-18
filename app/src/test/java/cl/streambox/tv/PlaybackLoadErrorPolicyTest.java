package cl.streambox.tv;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;

import org.junit.Test;

public class PlaybackLoadErrorPolicyTest {
    @Test
    public void cutSegmentIsRetriedAFewTimesBeforeGivingUp() {
        assertEquals(500L, PlaybackLoadErrorPolicy.positionRetryDelayMs(1));
        assertEquals(1_000L, PlaybackLoadErrorPolicy.positionRetryDelayMs(2));
        assertEquals(2_000L, PlaybackLoadErrorPolicy.positionRetryDelayMs(4));
        assertEquals(C.TIME_UNSET, PlaybackLoadErrorPolicy.positionRetryDelayMs(5));
    }
}
