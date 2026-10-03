package cl.streambox.tv;

import java.util.HashSet;
import java.util.Set;

/**
 * Canales que el usuario pidió ver con la señal gratuita tras el aviso de token
 * vencido. Dura hasta cerrar la app o volver a vincular.
 */
final class HighflyPremiumSession {
    private static final Set<String> FREE_ONLY = new HashSet<>();

    private HighflyPremiumSession() {}

    static synchronized void useFreeFor(String slug) {
        if (!AppStrings.isBlank(slug)) FREE_ONLY.add(slug);
    }

    static synchronized boolean isFreeOnly(String slug) {
        return slug != null && FREE_ONLY.contains(slug);
    }

    static synchronized void reset() {
        FREE_ONLY.clear();
    }
}
