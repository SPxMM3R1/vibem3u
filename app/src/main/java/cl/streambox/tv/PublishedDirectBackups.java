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
    // 0.6.58: 512 KiB quedó corto (Lista M3U llegó a 555 KB) y la descarga fallaba sin aviso.
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_PER_CHANNEL = 24;
    private static final int MAX_CHANNELS = 1024;

    private static volatile Map<String, List<Channel>> byChannel = Collections.emptyMap();
    private static volatile Map<String, Quality> qualityById = Collections.emptyMap();

    /**
     * Calidad medida de una señal (0.6.48): la publica Lista M3U en backups.json
     * ({@code principal_quality} y {@code backups[].quality}, scripts/measure_backup_quality.py).
     * Con ella el selector dibuja una barra por señal con alto = bitrate («V2 · Pulso»).
     */
    static final class Quality {
        final int height;
        final int kbps;
        final float fps;
        final boolean ok;
        /** Medida en la app al abrir el selector (0.6.49), no la diaria de backups.json. */
        final boolean live;
        /** Señal entrelazada (0.6.56): «59.9 fps» + «INTERLACED». */
        final boolean interlaced;
        /** Se sabe si es entrelazada o progresiva (si no, no se muestra etiqueta). */
        final boolean scanKnown;

        Quality(int height, int kbps, float fps, boolean ok, boolean live) {
            this(height, kbps, fps, ok, live, false, false);
        }

        Quality(int height, int kbps, float fps, boolean ok, boolean live, boolean interlaced,
                boolean scanKnown) {
            this.height = height;
            this.kbps = kbps;
            this.fps = fps;
            this.ok = ok;
            this.live = live;
            this.interlaced = interlaced;
            this.scanKnown = scanKnown;
        }
    }

    private PublishedDirectBackups() {
    }

    /** Reemplaza las copias vigentes; un documento inválido las deja vacías. */
    static void update(String json) {
        byChannel = parse(json);
        qualityById = parseQuality(json);
    }

    /** Calidad medida del principal o de una copia (por tvg-id); null si no hay medición. */
    static Quality qualityFor(String tvgId) {
        return tvgId == null ? null : qualityById.get(tvgId);
    }

    static Map<String, Quality> parseQuality(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_BYTES) return Collections.emptyMap();
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schema", 0) != 1) return Collections.emptyMap();
            JSONObject channels = root.optJSONObject("channels");
            if (channels == null) return Collections.emptyMap();
            Map<String, Quality> result = new HashMap<>();
            Iterator<String> keys = channels.keys();
            while (keys.hasNext() && result.size() < MAX_CHANNELS * (MAX_PER_CHANNEL + 1)) {
                String key = keys.next().trim();
                JSONObject entry = channels.optJSONObject(key);
                if (entry == null || key.isEmpty()) continue;
                Quality principal = qualityOf(entry.optJSONObject("principal_quality"));
                if (principal != null) result.put(key, principal);
                JSONArray items = entry.optJSONArray("backups");
                for (int index = 0; items != null && index < items.length(); index++) {
                    JSONObject copy = items.optJSONObject(index);
                    if (copy == null) continue;
                    String id = copy.optString("tvg_id", "").trim();
                    Quality quality = qualityOf(copy.optJSONObject("quality"));
                    if (!id.isEmpty() && quality != null) result.put(id, quality);
                }
            }
            return result;
        } catch (Exception invalid) {
            return Collections.emptyMap();
        }
    }

    private static Quality qualityOf(JSONObject value) {
        if (value == null) return null;
        int height = Math.max(0, Math.min(8640, value.optInt("height", 0)));
        int kbps = Math.max(0, Math.min(200_000, value.optInt("kbps", 0)));
        float fps = (float) Math.max(0d, Math.min(240d, value.optDouble("fps", 0d)));
        return new Quality(height, kbps, fps, value.optBoolean("ok", false) && height > 0, false,
                value.optBoolean("interlaced", false), value.has("interlaced"));
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
