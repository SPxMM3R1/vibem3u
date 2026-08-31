package cl.streambox.tv;

/** Bounded primitive handoff: the video callback never allocates a task or waits for calculations. */
final class FrameTimestampBuffer {
    private final long[] timestamps;
    private int head;
    private int size;

    FrameTimestampBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        timestamps = new long[capacity];
    }

    synchronized void add(long timestampNs) {
        if (timestampNs < 0L) return;
        if (size == timestamps.length) {
            // Prefer recent measurements if the worker was starved. Never build an unbounded
            // queue of obsolete per-frame callbacks that could delay playback or a new channel.
            head = (head + 1) % timestamps.length;
            size--;
        }
        timestamps[(head + size) % timestamps.length] = timestampNs;
        size++;
    }

    synchronized int drainTo(long[] destination) {
        int count = Math.min(size, destination.length);
        for (int index = 0; index < count; index++) {
            destination[index] = timestamps[(head + index) % timestamps.length];
        }
        head = (head + count) % timestamps.length;
        size -= count;
        return count;
    }
}
