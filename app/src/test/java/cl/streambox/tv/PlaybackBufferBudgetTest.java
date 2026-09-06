package cl.streambox.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackBufferBudgetTest {
    private static final long MIB = 1024L * 1024L;
    @Test public void reservesMostOfHeapForUiAndDecoders() {
        assertEquals(32 * MIB, PlaybackBufferBudget.targetBytes(192 * MIB, false));
        assertEquals(64 * MIB, PlaybackBufferBudget.targetBytes(384 * MIB, false));
        assertEquals(96 * MIB, PlaybackBufferBudget.targetBytes(1024 * MIB, false));
    }
    @Test public void lowRamHasSmallerCeilingAndArithmeticDoesNotOverflow() {
        assertEquals(32 * MIB, PlaybackBufferBudget.targetBytes(1024 * MIB, true));
        assertEquals(96 * MIB, PlaybackBufferBudget.targetBytes(Long.MAX_VALUE, false));
        assertEquals(8 * MIB, PlaybackBufferBudget.targetBytes(0, true));
    }
}
