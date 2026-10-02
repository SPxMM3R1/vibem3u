package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Versiones hermanas de cada canal TvVoo elegido, publicadas por el runner de Lista M3U
 * (data/tvvoo-variantes.json).
 *
 * <p>TvVoo lista varias entradas del mismo canal en un país («SKY SPORTS MIX», «… HD»,
 * «… FHD», «… (BACKUP)»), cada una con su propia fuente en Vavoo. El resolutor prueba
 * primero la versión elegida en el editor y, si no entrega video, estas hermanas. El
 * archivo solo trae alias del catálogo público: nada de URL de reproducción ni tokens.
 * Sin archivo (o con uno viejo) todo funciona como antes, solo con la versión elegida.
 */
final class PublishedTvVooVariants {
    static final int MAX_BYTES = 256 * 1024;
    /** Las variantes cambian poco; un archivo de más de una semana ya no se usa. */
    static final long MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    static final int MAX_SIBLINGS = 7;

    private static volatile Map<String, List<String>> siblings = Collections.emptyMap();

    private PublishedTvVooVariants() {
    }

    /** Reemplaza las variantes vigentes; un documento inválido o viejo las deja vacías. */
    static void update(String json, long nowMillis) {
        siblings = parse(json, nowMillis);
    }

    static Map<String, List<String>> parse(String json, long nowMillis) {
        if (json == null || json.length() > MAX_BYTES) return Collections.emptyMap();
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schema", 0) != 1) return Collections.emptyMap();
            long generatedAt = Instant.parse(root.optString("generatedAt", "")).toEpochMilli();
            if (nowMillis - generatedAt > MAX_AGE_MILLIS) return Collections.emptyMap();
            JSONObject channels = root.optJSONObject("channels");
            if (channels == null) return Collections.emptyMap();
            Map<String, List<String>> result = new HashMap<>();
            Iterator<String> keys = channels.keys();
            while (keys.hasNext() && result.size() < 512) {
                String key = keys.next();
                if (!isStableKey(key)) continue;
                JSONArray aliases = channels.optJSONArray(key);
                if (aliases == null) continue;
                List<String> safe = new ArrayList<>();
                for (int index = 0; index < aliases.length() && safe.size() < MAX_SIBLINGS; index++) {
                    String alias = aliases.optString(index, "").trim();
                    if (TvVooSourceHistory.isSafeAlias(alias) && !safe.contains(alias)) safe.add(alias);
                }
                if (!safe.isEmpty()) result.put(key, Collections.unmodifiableList(safe));
            }
            return Collections.unmodifiableMap(result);
        } catch (JSONException | DateTimeParseException | IllegalArgumentException invalid) {
            return Collections.emptyMap();
        }
    }

    /** ``countryKey|vavoo_…``: la misma forma de identidad que publica el editor. */
    private static boolean isStableKey(String key) {
        int separator = key == null ? -1 : key.indexOf('|');
        return separator > 0
                && key.indexOf('|', separator + 1) < 0
                && TvVooSourceHistory.isSafeAlias(key.substring(separator + 1));
    }

    /** Hermanas del canal (por su identidad ``countryKey|alias``); vacío si no hay. */
    static List<String> siblingsOf(String stableId) {
        if (stableId == null) return Collections.emptyList();
        List<String> found = siblings.get(stableId.trim());
        return found == null ? Collections.emptyList() : found;
    }

    static void resetForTests() {
        siblings = Collections.emptyMap();
    }
}
