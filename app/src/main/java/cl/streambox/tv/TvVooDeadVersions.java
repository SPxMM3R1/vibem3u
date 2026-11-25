package cl.streambox.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versiones TvVoo que fallaron de verdad (sin enlaces, 502 o sin respuesta): se dejan al
 * final durante 10 minutos para no gastar turnos ni reintentos en ellas. Solo memoria;
 * se olvida al cerrar la app o cuando la versión vuelve a entregar video.
 */
final class TvVooDeadVersions {
    static final long DEAD_FOR_MILLIS = 10L * 60_000L;
    private static final Map<String, Long> DEAD_UNTIL = new ConcurrentHashMap<>();

    private TvVooDeadVersions() {}

    static void markDead(String stableId, String alias, long nowMillis) {
        if (alias == null || alias.isEmpty()) return;
        DEAD_UNTIL.put(key(stableId, alias), nowMillis + DEAD_FOR_MILLIS);
    }

    static void markAlive(String stableId, String alias) {
        if (alias != null) DEAD_UNTIL.remove(key(stableId, alias));
    }

    static boolean isDead(String stableId, String alias, long nowMillis) {
        Long until = DEAD_UNTIL.get(key(stableId, alias));
        if (until == null) return false;
        if (until <= nowMillis) {
            DEAD_UNTIL.remove(key(stableId, alias));
            return false;
        }
        return true;
    }

    /** Mismo orden, con las muertas al final, y como máximo {@code max} versiones. */
    static List<String> order(String stableId, List<String> aliases, int max, long nowMillis) {
        List<String> alive = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (String alias : aliases) {
            (isDead(stableId, alias, nowMillis) ? dead : alive).add(alias);
        }
        alive.addAll(dead);
        return new ArrayList<>(alive.subList(0, Math.min(Math.max(1, max), alive.size())));
    }

    static void resetForTests() {
        DEAD_UNTIL.clear();
    }

    private static String key(String stableId, String alias) {
        return (stableId == null ? "" : stableId) + "\n" + alias;
    }
}
