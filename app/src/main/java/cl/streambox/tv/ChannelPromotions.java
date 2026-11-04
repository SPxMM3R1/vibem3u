package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Respaldos que el usuario pasó a principal desde esta TV (0.6.58). El cambio se ve al tiro: mientras
 * el catálogo publicado siga trayendo la señal anterior, la TV reproduce el respaldo con la identidad
 * del canal (mismo número, logo y guía). Cuando la fila anterior deja de venir, Lista M3U ya aplicó
 * el pedido y la entrada se borra sola. Si no se aplica en el plazo, el canal vuelve a como estaba.
 */
final class ChannelPromotions {
    private static final String PREFS = "channel_promotions";
    private static final String KEY = "entries";
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_STEPS = 32;

    static final class Step {
        final String tvgId;
        final URI uri;

        Step(String tvgId, URI uri) {
            this.tvgId = tvgId;
            this.uri = uri;
        }
    }

    /** {@code steps}: en orden; se puede pasar a principal varias veces antes de que llegue la lista. */
    static final class Entry {
        final String originId;
        final long atMillis;
        final List<Step> steps;

        Entry(String originId, long atMillis, List<Step> steps) {
            this.originId = originId;
            this.atMillis = atMillis;
            this.steps = steps;
        }
    }

    private final SharedPreferences preferences;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    ChannelPromotions(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        entries.putAll(parse(preferences.getString(KEY, "")));
    }

    synchronized void add(String originId, String backupTvgId, URI backupUri, long nowMillis) {
        Entry previous = entries.remove(originId);
        List<Step> steps = previous == null ? new ArrayList<>() : new ArrayList<>(previous.steps);
        steps.add(new Step(backupTvgId, backupUri));
        entries.put(originId, new Entry(originId, nowMillis, steps));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        save();
    }

    /** tvg-id de las señales ya promovidas de ese canal, en orden; el último es el principal real. */
    synchronized List<String> promotedIds(String originId, long nowMillis) {
        Entry entry = entries.get(originId);
        if (entry == null || expired(entry, nowMillis)) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        for (Step step : entry.steps) ids.add(step.tvgId);
        return ids;
    }

    synchronized Channel apply(Channel channel, long nowMillis) {
        Entry entry = channel == null ? null : entries.get(channel.getTvgId());
        if (entry == null || expired(entry, nowMillis)) return channel;
        List<URI> promoted = new ArrayList<>();
        for (Step step : entry.steps) promoted.add(step.uri);
        return TvVooBackup.withPromotedBackup(channel, promoted);
    }

    /** Borra lo que el catálogo ya aplicó (la fila anterior no viene) o lo que venció. */
    synchronized void prune(Collection<String> presentKeys, long nowMillis) {
        boolean changed = false;
        for (java.util.Iterator<Entry> iterator = entries.values().iterator(); iterator.hasNext(); ) {
            Entry entry = iterator.next();
            if (!presentKeys.contains("m3u:" + entry.originId) || expired(entry, nowMillis)) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    private static boolean expired(Entry entry, long nowMillis) {
        return nowMillis - entry.atMillis >= ChannelVisibility.TRASH_PENDING_MAX_MS;
    }

    private void save() {
        preferences.edit().putString(KEY, serialize(entries.values())).apply();
    }

    static String serialize(Collection<Entry> values) {
        JSONArray array = new JSONArray();
        for (Entry entry : values) {
            try {
                JSONArray steps = new JSONArray();
                for (Step step : entry.steps) {
                    steps.put(new JSONObject().put("id", step.tvgId).put("url", step.uri.toString()));
                }
                array.put(new JSONObject().put("origin", entry.originId).put("at", entry.atMillis).put("steps", steps));
            } catch (org.json.JSONException ignored) {
                // Un valor no serializable se omite.
            }
        }
        return array.toString();
    }

    static Map<String, Entry> parse(String text) {
        Map<String, Entry> result = new LinkedHashMap<>();
        if (AppStrings.isBlank(text) || text.length() > 256 * 1024) return result;
        try {
            JSONArray array = new JSONArray(text);
            for (int index = 0; index < array.length() && result.size() < MAX_ENTRIES; index++) {
                JSONObject value = array.optJSONObject(index);
                String origin = value == null ? "" : value.optString("origin", "").trim();
                JSONArray items = value == null ? null : value.optJSONArray("steps");
                if (origin.isEmpty() || items == null) continue;
                List<Step> steps = new ArrayList<>();
                for (int step = 0; step < items.length() && steps.size() < MAX_STEPS; step++) {
                    JSONObject item = items.optJSONObject(step);
                    if (item == null) continue;
                    String id = item.optString("id", "").trim();
                    String url = item.optString("url", "").trim();
                    if (id.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) continue;
                    try {
                        steps.add(new Step(id, URI.create(url)));
                    } catch (IllegalArgumentException invalid) {
                        // Dirección dañada: esa señal se omite.
                    }
                }
                if (!steps.isEmpty()) result.put(origin, new Entry(origin, value.optLong("at", 0L), steps));
            }
        } catch (org.json.JSONException ignored) {
            // Preferencia dañada: se parte de cero (el catálogo publicado manda).
        }
        return result;
    }
}
