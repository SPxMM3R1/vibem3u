package cl.streambox.tv;

/**
 * Arranque lento (0.6.04): la señal principal lleva demasiado sin entregar su lista y el canal
 * tiene respaldo, así que se prueba el respaldo sin esperar a que la principal falle. Medido en
 * el emulador: la lista llega en 0,2–1,1 s; los servidores lentos tardaron 2,4–10 s.
 */
final class SlowStartBackupPolicy {
    static final long WAIT_MS = 5_000L;

    private SlowStartBackupPolicy() {
    }

    /**
     * @param alreadyTried   ya se probó en esta apertura (solo una vez por canal)
     * @param onBackup       ya se está en un respaldo
     * @param hasBackup      el canal tiene respaldo
     * @param slowRelay      relé lento a propósito (Nauta): espera más antes de arrancar
     * @param sourceStartedMs inicio de la señal actual (elapsedRealtime), 0 o menos si no hay
     * @param manifestMs     ms hasta la primera lista, negativo si todavía no llegó
     */
    static boolean shouldTryBackup(boolean alreadyTried, boolean onBackup, boolean hasBackup,
            boolean slowRelay, long sourceStartedMs, long nowMs, long manifestMs) {
        if (alreadyTried || onBackup || !hasBackup || slowRelay) return false;
        if (sourceStartedMs <= 0L || nowMs - sourceStartedMs < WAIT_MS) return false;
        return manifestMs < 0L;
    }
}
