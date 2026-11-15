package cl.streambox.tv;

import java.util.HashSet;
import java.util.Set;

/**
 * Decide qué hacer cuando una señal se traba poco después de empezar a verse (0.6.67).
 *
 * <p>Antes, un corte a los pocos segundos saltaba de inmediato al respaldo, y el respaldo
 * también se trababa a los pocos segundos: una cascada de respaldos en un canal que el
 * decodificador de la TV no alcanza. Ahora:
 * <ul>
 *   <li>el primer corte temprano de una fuente se reconecta a la misma fuente
 *       ({@link Action#RECONNECT_SAME});</li>
 *   <li>un segundo corte temprano de esa fuente la da por perdida y pasa al respaldo
 *       ({@link Action#SWITCH_SOURCE});</li>
 *   <li>si dos fuentes seguidas se traban por «decoder detenido» dentro de la ventana, no se
 *       cambia más de fuente ({@link Action#STOP_CASCADE}): se queda en la principal.</li>
 * </ul>
 * Un corte fuera de la ventana (la señal ya andaba) reinicia todo y sigue el comportamiento
 * de siempre.
 */
final class EarlyStallPolicy {
    /** Fuentes seguidas que terminan por «decoder detenido» dentro de la ventana antes de frenar. */
    static final int CASCADE_LIMIT = 2;

    enum Action {
        /** Reconectar la misma fuente (consume un intento del presupuesto). */
        RECONNECT_SAME,
        /** Pasar al respaldo, como antes. */
        SWITCH_SOURCE,
        /** Dos fuentes seguidas se trabaron al empezar: quedarse en la principal. */
        STOP_CASCADE
    }

    private final Set<String> retriedSources = new HashSet<>();
    private int consecutiveDecoderStops;

    /** Olvida fuentes reconectadas y el contador (canal nuevo, señal estable o reintento manual). */
    void reset() {
        retriedSources.clear();
        consecutiveDecoderStops = 0;
    }

    /**
     * Ventana temprana: el corte ocurre dentro de los primeros {@link
     * PlaybackRecoveryEpisode#STABLE_PLAYBACK_MS} desde el primer cuadro de la fuente.
     * Sin primer cuadro ({@code firstFrameElapsedMs} &lt; 0) no hay ventana.
     */
    static boolean isEarly(long firstFrameElapsedMs, long nowElapsedMs) {
        return firstFrameElapsedMs >= 0L
                && nowElapsedMs >= firstFrameElapsedMs
                && nowElapsedMs - firstFrameElapsedMs < PlaybackRecoveryEpisode.STABLE_PLAYBACK_MS;
    }

    /**
     * Veredicto ante un corte. {@code sourceKey} identifica la fuente (canal y señal);
     * {@code decoderStall} es verdadero solo para el corte «decoder detenido» del vigía.
     */
    Action onStall(String sourceKey, boolean early, boolean decoderStall) {
        if (!early) {
            // La señal anduvo lo suficiente: el historial de cortes tempranos ya no aplica.
            reset();
            return Action.SWITCH_SOURCE;
        }
        if (retriedSources.add(sourceKey)) {
            return Action.RECONNECT_SAME;
        }
        // Segundo corte temprano de esta fuente: se da por perdida.
        consecutiveDecoderStops = decoderStall ? consecutiveDecoderStops + 1 : 0;
        return decoderStall && consecutiveDecoderStops >= CASCADE_LIMIT
                ? Action.STOP_CASCADE
                : Action.SWITCH_SOURCE;
    }

    /** Fuentes seguidas terminadas por «decoder detenido» dentro de la ventana. */
    int consecutiveDecoderStops() {
        return consecutiveDecoderStops;
    }
}
