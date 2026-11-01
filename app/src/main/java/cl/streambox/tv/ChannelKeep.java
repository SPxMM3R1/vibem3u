package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Canales marcados «Conservar» (0.6.52, pedido del usuario: «en vez de eliminar haya otro botón
 * que diga conservar y que al final yo te pueda decir, todo lo que no se conservó, elimínalo»).
 *
 * <p>La lista oficial es {@code data/app-keep.json} de Lista M3U (filas del editor). Lo marcado en
 * esta TV vale de inmediato y se mantiene como pendiente hasta que GitHub lo publica: el workflow
 * de pedidos y la caché de raw.githubusercontent.com tardan unos minutos.
 */
final class ChannelKeep {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Si GitHub no lo refleja en este plazo, manda lo publicado (algo falló). */
    static final long PENDING_MAX_MS = 30L * 60L * 1000L;
    private static final String PREFS = "channel_keep";
    private static final String KEY_REMOTE = "remote";
    private static final String KEY_PENDING = "pending";

    private final SharedPreferences preferences;
    private final Set<String> remote = new LinkedHashSet<>();
    /** Clave → {keep, at}: lo marcado aquí que GitHub todavía no publica. */
    private final Map<String, long[]> pending = new LinkedHashMap<>();

    ChannelKeep(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        remote.addAll(parseRemote(preferences.getString(KEY_REMOTE, "")));
        parsePending(preferences.getString(KEY_PENDING, ""));
    }

    synchronized boolean isKept(String key, long nowMillis) {
        if (AppStrings.isBlank(key)) return false;
        long[] mark = pending.get(key);
        if (mark != null && nowMillis - mark[1] < PENDING_MAX_MS) return mark[0] == 1L;
        return remote.contains(key);
    }

    synchronized int count(long nowMillis) {
        Set<String> kept = new LinkedHashSet<>(remote);
        for (Map.Entry<String, long[]> entry : pending.entrySet()) {
            if (nowMillis - entry.getValue()[1] >= PENDING_MAX_MS) continue;
            if (entry.getValue()[0] == 1L) kept.add(entry.getKey());
            else kept.remove(entry.getKey());
        }
        return kept.size();
    }

    synchronized void mark(String key, boolean keep, long nowMillis) {
        if (AppStrings.isBlank(key)) return;
        pending.put(key, new long[] {keep ? 1L : 0L, nowMillis});
        save();
    }

    /** Lista publicada en GitHub: los pendientes que ya refleja (o vencidos) se descartan. */
    synchronized void applyRemote(Collection<String> published, long nowMillis) {
        remote.clear();
        remote.addAll(published);
        Iterator<Map.Entry<String, long[]>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, long[]> entry = iterator.next();
            boolean keep = entry.getValue()[0] == 1L;
            if (remote.contains(entry.getKey()) == keep || nowMillis - entry.getValue()[1] >= PENDING_MAX_MS) {
                iterator.remove();
            }
        }
        save();
    }

    static Set<String> parseRemote(String document) {
        Set<String> keys = new LinkedHashSet<>();
        if (AppStrings.isBlank(document)) return keys;
        try {
            JSONArray rows = new JSONObject(document).optJSONArray("rowKeys");
            if (rows == null) return keys;
            for (int index = 0; index < rows.length(); index++) {
                String key = rows.optString(index, "").trim();
                if (!key.isEmpty() && key.length() <= 600) keys.add(key);
            }
        } catch (JSONException invalid) {
            return new LinkedHashSet<>();
        }
        return keys;
    }

    private void parsePending(String document) {
        if (AppStrings.isBlank(document)) return;
        try {
            JSONObject object = new JSONObject(document);
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                JSONArray value = object.optJSONArray(key);
                if (value != null && value.length() == 2) {
                    pending.put(key, new long[] {value.optLong(0), value.optLong(1)});
                }
            }
        } catch (JSONException ignored) {
            pending.clear();
        }
    }

    private void save() {
        JSONArray rows = new JSONArray();
        for (String key : remote) rows.put(key);
        JSONObject marks = new JSONObject();
        try {
            for (Map.Entry<String, long[]> entry : pending.entrySet()) {
                marks.put(entry.getKey(), new JSONArray().put(entry.getValue()[0]).put(entry.getValue()[1]));
            }
            preferences.edit()
                    .putString(KEY_REMOTE, new JSONObject().put("rowKeys", rows).toString())
                    .putString(KEY_PENDING, marks.toString())
                    .apply();
        } catch (JSONException ignored) {
            // Sin guardar, lo marcado sigue valiendo mientras la app esté abierta.
        }
    }
}
