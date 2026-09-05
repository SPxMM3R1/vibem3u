package cl.streambox.tv;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounds fresh reconnections for each temporary Premium event independently.
 * The counter is intentionally session-only; the selected event identity is
 * persisted separately and is removed only after the recovery budget is
 * exhausted.
 */
final class HighflyPremiumEventRecoveryPolicy {
    static final int MAX_RECONNECTION_ATTEMPTS = 3;

    private final Map<String, Integer> attemptsByEvent = new HashMap<>();

    synchronized boolean tryConsume(String eventId) {
        String key = normalize(eventId);
        if (key.isBlank()) return false;
        int attempts = attemptsByEvent.containsKey(key)
                ? attemptsByEvent.get(key)
                : 0;
        if (attempts >= MAX_RECONNECTION_ATTEMPTS) return false;
        attemptsByEvent.put(key, attempts + 1);
        return true;
    }

    synchronized int attemptsFor(String eventId) {
        String key = normalize(eventId);
        if (key.isBlank()) return 0;
        Integer attempts = attemptsByEvent.get(key);
        return attempts == null ? 0 : attempts;
    }

    /** Called only after sustained playback confirms the event recovered. */
    synchronized void markAvailable(String eventId) {
        String key = normalize(eventId);
        if (!key.isBlank()) attemptsByEvent.remove(key);
    }

    synchronized void clear(String eventId) {
        String key = normalize(eventId);
        if (!key.isBlank()) attemptsByEvent.remove(key);
    }

    synchronized void clearAll() {
        attemptsByEvent.clear();
    }

    private static String normalize(String eventId) {
        return eventId == null ? "" : eventId.trim();
    }
}
