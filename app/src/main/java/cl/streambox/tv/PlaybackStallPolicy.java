package cl.streambox.tv;

/** Decides when post-start buffering has lasted long enough to reopen playback. */
final class PlaybackStallPolicy {
    private PlaybackStallPolicy() {}

    static boolean isExpired(long bufferingSinceElapsedRealtime, long nowElapsedRealtime,
            long timeoutMs) {
        return bufferingSinceElapsedRealtime >= 0L
                && timeoutMs > 0L
                && nowElapsedRealtime >= bufferingSinceElapsedRealtime
                && nowElapsedRealtime - bufferingSinceElapsedRealtime >= timeoutMs;
    }
}
