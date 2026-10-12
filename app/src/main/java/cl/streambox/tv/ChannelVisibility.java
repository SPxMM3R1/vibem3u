package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canales que esta TV no muestra (0.5.90), por clave de fila del editor web
 * ({@code m3u:<tvg-id>} o {@code provider:<proveedor>:<catalogKey>}).
 *
 * <ul>
 *   <li><b>Oculto</b>: decisión solo de esta TV; se deshace en Opciones › Canales.</li>
 *   <li><b>En papelera</b>: se pidió a GitHub; mientras el catálogo publicado lo siga
 *       trayendo, la TV lo esconde igual. Cuando deja de venir, la entrada se borra sola.</li>
 * </ul>
 */
final class ChannelVisibility {
    static final String HIDDEN = "hidden";
    static final String TRASHED = "trashed";
    /** Si el runner no lo retira en este plazo, el canal vuelve a verse (algo falló). */
    static final long TRASH_PENDING_MAX_MS = 3L * 24L * 60L * 60L * 1000L;
    private static final String PREFS = "channel_visibility";
    private static final String KEY = "entries";
    private static final int MAX_ENTRIES = 500;

    static final class Entry {
        final String key;
        final String name;
        final String state;
        final long atMillis;

        Entry(String key, String name, String state, long atMillis) {
            this.key = key;
            this.name = name;
            this.state = state;
            this.atMillis = atMillis;
        }
    }

    private final SharedPreferences preferences;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    ChannelVisibility(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        entries.putAll(parse(preferences.getString(KEY, "")));
    }

    /** Huella del estado guardado: si cambia en Opciones, la lista de canales se rehace. */
    synchronized String signature() {
        return serialize(entries.values());
    }

    synchronized boolean isHidden(String key, long nowMillis) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) return false;
        return HIDDEN.equals(entry.state) || nowMillis - entry.atMillis < TRASH_PENDING_MAX_MS;
    }

    synchronized void hide(String key, String name, long nowMillis) {
        put(new Entry(key, name, HIDDEN, nowMillis));
    }

    synchronized void markTrashed(String key, String name, long nowMillis) {
        put(new Entry(key, name, TRASHED, nowMillis));
    }

    synchronized void show(String key) {
        if (entries.remove(key) != null) save();
    }

    /** Ocultos por el usuario (los de la papelera no se muestran en Opciones). */
    synchronized List<Entry> hiddenEntries() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) if (HIDDEN.equals(entry.state)) result.add(entry);
        return Collections.unmodifiableList(result);
    }

    /**
     * Borra las entradas de papelera cuyo canal ya no viene en el catálogo (el runner lo
     * retiró) o que vencieron sin que se retirara.
     */
    synchronized void prune(Collection<String> presentKeys, long nowMillis) {
        boolean changed = false;
        for (java.util.Iterator<Entry> iterator = entries.values().iterator(); iterator.hasNext(); ) {
            Entry entry = iterator.next();
            if (!TRASHED.equals(entry.state)) continue;
            if (!presentKeys.contains(entry.key) || nowMillis - entry.atMillis >= TRASH_PENDING_MAX_MS) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    private void put(Entry entry) {
        if (AppStrings.isBlank(entry.key)) return;
        entries.remove(entry.key);
        entries.put(entry.key, entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        save();
    }

    private void save() {
        preferences.edit().putString(KEY, serialize(entries.values())).apply();
    }

    static String serialize(Collection<Entry> values) {
        JSONArray array = new JSONArray();
        for (Entry entry : values) {
            try {
                array.put(new JSONObject()
                        .put("key", entry.key)
                        .put("name", entry.name == null ? "" : entry.name)
                        .put("state", entry.state)
                        .put("at", entry.atMillis));
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
                if (value == null) continue;
                String key = value.optString("key", "").trim();
                String state = value.optString("state", "");
                if (key.isEmpty() || key.length() > 600
                        || !(HIDDEN.equals(state) || TRASHED.equals(state))) continue;
                result.put(key, new Entry(key, value.optString("name", ""), state, value.optLong("at", 0L)));
            }
        } catch (org.json.JSONException ignored) {
            // Preferencia dañada: se parte de cero (todos los canales visibles).
        }
        return result;
    }
}
