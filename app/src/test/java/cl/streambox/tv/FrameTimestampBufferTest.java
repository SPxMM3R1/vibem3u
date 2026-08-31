package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class FrameTimestampBufferTest {
    @Test public void preservesOriginalTimestampsAndDrainsOnlyOnce() {
        FrameTimestampBuffer buffer = new FrameTimestampBuffer(4);
        buffer.add(100L);
        buffer.add(200L);
        buffer.add(300L);
        long[] result = new long[3];
        assertEquals(3, buffer.drainTo(result));
        assertArrayEquals(new long[] {100L, 200L, 300L}, result);
        assertEquals(0, buffer.drainTo(result));
    }

    @Test public void keepsOnlyRecentFramesWhenConsumerIsDelayed() {
        FrameTimestampBuffer buffer = new FrameTimestampBuffer(3);
        for (long timestamp = 0; timestamp < 8; timestamp++) buffer.add(timestamp);
        long[] result = new long[3];
        assertEquals(3, buffer.drainTo(result));
        assertArrayEquals(new long[] {5L, 6L, 7L}, result);
    }

    @Test public void supportsPartialReadsAndWraparound() {
        FrameTimestampBuffer buffer = new FrameTimestampBuffer(3);
        buffer.add(1L);
        buffer.add(2L);
        buffer.add(3L);
        long[] first = new long[2];
        assertEquals(2, buffer.drainTo(first));
        buffer.add(4L);
        buffer.add(5L);
        long[] rest = new long[3];
        assertEquals(3, buffer.drainTo(rest));
        assertArrayEquals(new long[] {3L, 4L, 5L}, rest);
    }

    @Test public void ignoresInvalidTimestamps() {
        FrameTimestampBuffer buffer = new FrameTimestampBuffer(1);
        buffer.add(-1L);
        assertEquals(0, buffer.drainTo(new long[1]));
    }
}
