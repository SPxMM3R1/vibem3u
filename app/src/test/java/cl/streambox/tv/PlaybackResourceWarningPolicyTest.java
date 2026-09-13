package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackResourceWarningPolicyTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    public void ignoresLowBufferDuringStartup() {
        assertFalse(PlaybackResourceWarningPolicy.isBufferWarning(
                false, false, true, 0L, 1_000L, 5_000L));
    }

    @Test
    public void waitsForSustainedLowBuffer() {
        assertFalse(PlaybackResourceWarningPolicy.isBufferWarning(
                true, false, true, 500L, 1_000L, 2_499L));
        assertTrue(PlaybackResourceWarningPolicy.isBufferWarning(
                true, false, true, 500L, 1_000L, 2_500L));
    }

    @Test
    public void ignoresPausedPlaybackAndHealthyBuffer() {
        assertFalse(PlaybackResourceWarningPolicy.isBufferWarning(
                true, true, true, 0L, 1_000L, 5_000L));
        assertFalse(PlaybackResourceWarningPolicy.isBufferWarning(
                true, false, true, 751L, 1_000L, 5_000L));
        assertFalse(PlaybackResourceWarningPolicy.isBufferWarning(
                true, false, false, 0L, 1_000L, 5_000L));
    }

    @Test
    public void reportsHeapAndAllocatorPressure() {
        assertEquals(
                PlaybackResourceWarningPolicy.Type.MEMORY,
                PlaybackResourceWarningPolicy.memoryType(
                        true, 0, 88L * MIB, 100L * MIB, 1L, 100L));
        assertEquals(
                PlaybackResourceWarningPolicy.Type.MEMORY,
                PlaybackResourceWarningPolicy.memoryType(
                        true, 0, 1L, 100L, 92L, 100L));
    }

    @Test
    public void reportsCriticalPressureSeparately() {
        assertEquals(
                PlaybackResourceWarningPolicy.Type.MEMORY_CRITICAL,
                PlaybackResourceWarningPolicy.memoryType(
                        true,
                        PlaybackResourceWarningPolicy.RUNNING_CRITICAL_MEMORY_LEVEL,
                        1L,
                        100L,
                        1L,
                        100L));
        assertEquals(
                PlaybackResourceWarningPolicy.Type.MEMORY_CRITICAL,
                PlaybackResourceWarningPolicy.memoryType(
                true, 0, 95L, 100L, 1L, 100L));
    }

    @Test
    public void ignoresBackgroundTrimLevelsWhenMemoryIsHealthy() {
        assertEquals(
                PlaybackResourceWarningPolicy.Type.NONE,
                PlaybackResourceWarningPolicy.memoryType(
                        true, 20, 50L, 100L, 50L, 100L));
    }
}
