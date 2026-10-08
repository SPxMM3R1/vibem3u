package cl.streambox.tv;

/** Decides when post-start buffering has lasted long enough to reopen playback. */
final class PlaybackStallPolicy {
    private PlaybackStallPolicy() {}

    static String classify(boolean buffering, boolean mediaStopped, boolean audioUnderrun,
            long bufferedMs) {
        if (buffering) return "carga detenida";
        if (mediaStopped && bufferedMs <= 500L) return "audio y vídeo detenidos";
        if (audioUnderrun && bufferedMs <= 500L) return "audio detenido";
        return "decoder detenido";
    }

    static boolean isExpired(long bufferingSinceElapsedRealtime, long nowElapsedRealtime,
            long timeoutMs) {
        return bufferingSinceElapsedRealtime >= 0L
                && timeoutMs > 0L
                && nowElapsedRealtime >= bufferingSinceElapsedRealtime
                && nowElapsedRealtime - bufferingSinceElapsedRealtime >= timeoutMs;
    }
}
