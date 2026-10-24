package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copias de Lista 2 que respaldan a un canal de Lista 1 (backups.json, publicado en el
 * repositorio). Cada copia es una dirección HTTP(S) directa con su propio tvg-id; se prueba en
 * el orden del archivo, después de las señales que el canal ya tenga. Sin archivo, o con uno
 * inválido, el canal funciona como antes.
 */
final class PublishedDirectBackups {
    static final int MAX_BYTES = 512 * 1024;
    static final int MAX_PER_CHANNEL = 8;
    private static final int MAX_CHANNELS = 1024;

    private static volatile Map<String, List<Channel>> byChannel = Collections.emptyMap();

    private PublishedDirectBackups() {
    }

    /** Reemplaza las copias vigentes; un documento inválido las deja vacías. */
    static void update(String json) {
        byChannel = parse(json);
    }

    /** Copias de respaldo del canal de Lista 1 con ese tvg-id, en orden; vacía si no tiene. */
    static List<Channel> backupsFor(String tvgId) {
        List<Channel> copies = tvgId == null ? null : byChannel.get(tvgId);
        return copies == null ? Collections.<Channel>emptyList() : copies;
    }

    static Map<String, List<Channel>> parse(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_BYTES) {
            return Collections.emptyMap();
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schema", 0) != 1) return Collections.emptyMap();
            JSONObject channels = root.optJSONObject("channels");
            if (channels == null) return Collections.emptyMap();
            Map<String, List<Channel>> result = new HashMap<>();
            Iterator<String> keys = channels.keys();
            while (keys.hasNext() && result.size() < MAX_CHANNELS) {
                String key = keys.next().trim();
                JSONObject entry = channels.optJSONObject(key);
                JSONArray items = entry == null ? null : entry.optJSONArray("backups");
                if (key.isEmpty() || items == null) continue;
                List<Channel> copies = new ArrayList<>();
                for (int index = 0; index < items.length() && copies.size() < MAX_PER_CHANNEL; index++) {
                    Channel copy = copyOf(items.optJSONObject(index));
                    if (copy != null) copies.add(copy);
                }
                if (!copies.isEmpty()) result.put(key, Collections.unmodifiableList(copies));
            }
            return result;
        } catch (Exception invalid) {
            return Collections.emptyMap();
        }
    }

    /** Una copia con su identidad propia; null si le falta algo o su dirección no es HTTP(S). */
    private static Channel copyOf(JSONObject value) {
        if (value == null) return null;
        String tvgId = value.optString("tvg_id", "").trim();
        String name = value.optString("display_name", "").trim();
        String url = value.optString("url", "").trim();
        if (tvgId.isEmpty() || tvgId.length() > 512 || name.isEmpty() || name.length() > 200) {
            return null;
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null;
        if (url.length() > 2048 || url.contains(" ")) return null;
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("tvg-id", tvgId);
            attributes.put("tvg-name", name);
            return new Channel(name, URI.create(url), null, value.optString("group", ""), attributes);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
