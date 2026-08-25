package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public final class HlsStreamValidatorTest {
    @Test
    public void probesPenultimateThenNewestSegment() {
        assertArrayEquals(new int[]{4, 5, 3}, HlsStreamValidator.recentProbeOrder(6));
    }

    @Test
    public void handlesShortLiveWindowsWithoutDuplicates() {
        assertArrayEquals(new int[]{0}, HlsStreamValidator.recentProbeOrder(1));
        assertArrayEquals(new int[]{0, 1}, HlsStreamValidator.recentProbeOrder(2));
    }
}
