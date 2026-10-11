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

    /**
     * Un corte con el segmento todavía llegando (bytes de video hace menos de
     * {@code receivingWindowMs}) se espera hasta {@code maxWhileReceivingMs} antes de
     * reiniciar (0.5.89): el reinicio vuelve al vivo sin búfer y un servidor lento nunca se
     * pone al día. Sin bytes recientes, se reinicia como siempre.
     */
    static boolean keepWaitingWhileReceiving(long bufferingSinceElapsedRealtime,
            long nowElapsedRealtime, long lastMediaBytesElapsedRealtime, long receivingWindowMs,
            long maxWhileReceivingMs) {
        if (bufferingSinceElapsedRealtime < 0L || lastMediaBytesElapsedRealtime < 0L) return false;
        if (nowElapsedRealtime - lastMediaBytesElapsedRealtime > receivingWindowMs) return false;
        return nowElapsedRealtime - bufferingSinceElapsedRealtime < maxWhileReceivingMs;
    }

    static boolean isExpired(long bufferingSinceElapsedRealtime, long nowElapsedRealtime,
            long timeoutMs) {
        return bufferingSinceElapsedRealtime >= 0L
                && timeoutMs > 0L
                && nowElapsedRealtime >= bufferingSinceElapsedRealtime
                && nowElapsedRealtime - bufferingSinceElapsedRealtime >= timeoutMs;
    }
}
