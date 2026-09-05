package cl.streambox.tv;

/** Budget for compressed samples, separate from decoder surfaces and the rest of the app heap. */
final class PlaybackBufferBudget {
    private static final long MIB = 1024L * 1024L;
    private PlaybackBufferBudget() {}

    static int targetBytes(long maxHeapBytes, boolean lowRamDevice) {
        long ceiling = (lowRamDevice ? 32L : 64L) * MIB;
        long share = Math.max(0L, maxHeapBytes) / 8L;
        return (int) Math.max(8L * MIB, Math.min(ceiling, share));
    }
}
