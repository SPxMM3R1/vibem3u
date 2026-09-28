package cl.streambox.tv;

/**
 * Presupuesto de reconexión automática de un canal.
 *
 * <p>Tras un error se reintenta hasta {@link #MAX_ATTEMPTS} veces con esperas
 * crecientes antes de mostrar ERROR. El primer intento reinicia el reproductor
 * con la misma fuente; los siguientes piden una fuente nueva al resolutor, por
 * si la anterior venció. El presupuesto se repone cuando el canal se reproduce
 * estable, al cambiar de canal o cuando el usuario pide reintentar.
 */
final class PlaybackRecoveryBudget {
    static final int MAX_ATTEMPTS = 3;
    private static final long[] DELAYS_MS = {2_000L, 5_000L, 10_000L};

    private int used;

    /** Reintentos ya usados en este episodio. */
    int used() {
        return used;
    }

    boolean hasAttemptLeft() {
        return used < MAX_ATTEMPTS;
    }

    /** Consume un intento y devuelve su número (1..MAX_ATTEMPTS). */
    int consume() {
        if (!hasAttemptLeft()) throw new IllegalStateException("sin reintentos");
        return ++used;
    }

    /** Espera antes del intento {@code attempt} (1..MAX_ATTEMPTS). */
    static long delayMsFor(int attempt) {
        int index = Math.max(1, Math.min(attempt, DELAYS_MS.length)) - 1;
        return DELAYS_MS[index];
    }

    /** Desde el segundo intento se descarta la fuente resuelta y se pide otra. */
    static boolean renewsSource(int attempt) {
        return attempt >= 2;
    }

    void reset() {
        used = 0;
    }

    /** Restaura lo usado cuando reabrir el canal reinicia el estado del episodio. */
    void restore(int previouslyUsed) {
        used = Math.max(0, Math.min(previouslyUsed, MAX_ATTEMPTS));
    }
}
