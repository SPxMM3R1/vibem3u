package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enlaces directos de Highfly publicados por el runner (data/highfly-live.json).
 *
 * <p>Con ellos la app abre un canal Highfly sin consultar la API ni validar la
 * lista: es el camino rápido. Solo se aceptan enlaces sin token con la forma
 * exacta {@code https://papacito.cfd/m3u/<slug>/live.m3u8}. Un enlace que falla
 * al reproducir se descarta por el resto de la sesión y el resolutor completo
 * vuelve a buscar la hoja vigente.
 */
final class PublishedHighflyLinks {
    static final int MAX_BYTES = 64 * 1024;
    /** Un archivo viejo no se usa: el runner lo renueva cada 30 minutos. */
    static final long MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L;
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final String LINK_PREFIX = "https://papacito.cfd/m3u/";

    private static volatile Map<String, URI> links = Collections.emptyMap();
    private static final Set<String> failed = Collections.synchronizedSet(new HashSet<>());

    private PublishedHighflyLinks() {
    }

    /** Reemplaza los enlaces vigentes; un documento inválido o viejo los deja vacíos. */
    static void update(String json, long nowMillis) {
        links = parse(json, nowMillis);
    }

    static Map<String, URI> parse(String json, long nowMillis) {
        if (json == null || json.length() > MAX_BYTES) return Collections.emptyMap();
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schema", 0) != 1) return Collections.emptyMap();
            long generatedAt = Instant.parse(root.optString("generatedAt", "")).toEpochMilli();
            if (nowMillis - generatedAt > MAX_AGE_MILLIS) return Collections.emptyMap();
            JSONArray channels = root.optJSONArray("channels");
            if (channels == null) return Collections.emptyMap();
            Map<String, URI> result = new HashMap<>();
            for (int index = 0; index < channels.length() && index < 256; index++) {
                JSONObject channel = channels.optJSONObject(index);
                if (channel == null) continue;
                String catalogKey = channel.optString("catalogKey", "").trim();
                String slug = channel.optString("slug", "").trim();
                String url = channel.optString("url", "").trim();
                if (catalogKey.isEmpty() || !SLUG.matcher(slug).matches()) continue;
                if (!url.equals(LINK_PREFIX + slug + "/live.m3u8")) continue;
                result.put(catalogKey, URI.create(url));
            }
            return Collections.unmodifiableMap(result);
        } catch (JSONException | DateTimeParseException | IllegalArgumentException invalid) {
            return Collections.emptyMap();
        }
    }

    /** Enlace directo del canal si existe y no falló en esta sesión; si no, null. */
    static URI usable(String catalogKey) {
        if (catalogKey == null) return null;
        URI link = links.get(catalogKey.trim());
        if (link == null || failed.contains(link.toString())) return null;
        return link;
    }

    /** El enlace no reprodujo: la próxima vez se usa el resolutor completo. */
    static void markFailed(URI link) {
        if (link != null) failed.add(link.toString());
    }

    static void resetForTests() {
        links = Collections.emptyMap();
        failed.clear();
    }
}
