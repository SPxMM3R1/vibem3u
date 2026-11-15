package cl.streambox.tv;

/**
 * Último instante en que avanzó el trabajo del decodificador (0.6.67).
 *
 * <p>El vigía de cuadros solo ve los cuadros que Media3 muestra. Con un decodificador lento,
 * Media3 descarta en silencio los cuadros que llegan tarde: no hay cuadro mostrado durante
 * segundos, pero el decodificador sigue trabajando. Por eso se suma también lo que el
 * decodificador procesó (mostrado, descartado y saltado) y se mira si ese total avanza.
 */
final class DecoderProgressTracker {
    /** Sin progreso observado (no hay cuadros del decodificador todavía). */
    static final long NO_PROGRESS = Long.MIN_VALUE;

    private long lastTotal = -1L;
    private long lastChangeNs;

    /** Total de trabajo del decodificador: mostrados + descartados + saltados (salida y entrada). */
    static long totalOf(int rendered, int dropped, int skippedOutput, int skippedInput) {
        return (long) rendered + dropped + skippedOutput + skippedInput;
    }

    /**
     * Registra el total actual. Devuelve el último instante en que el total cambió, o
     * {@link #NO_PROGRESS} si el decodificador aún no produjo nada.
     */
    long observe(long total, long nowNs) {
        if (total <= 0L) return NO_PROGRESS;
        if (total != lastTotal) {
            lastTotal = total;
            lastChangeNs = nowNs;
        }
        return lastChangeNs;
    }

    /** Canal, fuente o reproductor nuevos: el contador vuelve a empezar. */
    void reset() {
        lastTotal = -1L;
        lastChangeNs = 0L;
    }
}
