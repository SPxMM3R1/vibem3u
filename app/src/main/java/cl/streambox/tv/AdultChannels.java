package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Canales de adultos con clave (0.6.36). La lista de tvg-id viene publicada en adultos.json; la
 * app solo trae la huella PBKDF2 de la clave que eligió el usuario, nunca la clave. Bloqueados,
 * esos canales no entran a la lista (ni a la guía ni a la búsqueda); desbloqueados, quedan
 * visibles en esta tele hasta que se bloqueen en Opciones › Canales. Nadie puede crear ni
 * cambiar la clave desde la app: para cambiarla hay que publicar otra versión.
 */
final class AdultChannels {
    static final int MAX_BYTES = 128 * 1024;
    static final int MIN_DIGITS = 4;
    static final int MAX_DIGITS = 12;
    static final int MAX_FAILURES = 5;
    static final long LOCKOUT_MS = 30_000L;
    static final int ITERATIONS = 200_000;
    /** Grupo de Lista 2 que se oculta mientras la lista publicada no ha llegado. */
    static final String FALLBACK_GROUP = "Adultos";
    private static final String SALT_HEX = "92ec73e4f3693eecbd2f03d4d2f45eaa";
    private static final String HASH_HEX =
            "770e396773abb55ca4ef2a336440b8f37ae259398e564955b227f4ca44bc76fa";
    private static final String PREFS = "adult_channels";
    private static final String KEY_UNLOCKED = "unlocked";
    private static final String KEY_FAILURES = "failures";
    private static final String KEY_LOCKED_UNTIL = "locked_until";

    private static volatile Set<String> published = Collections.emptySet();

    private AdultChannels() {
    }

    /** Reemplaza la lista publicada; true si cambió. Un documento inválido no borra la vigente. */
    static boolean update(String json) {
        Set<String> next = parse(json);
        if (next.isEmpty() || next.equals(published)) return false;
        published = next;
        return true;
    }

    /**
     * Canal de adultos. Sin lista publicada todavía (primer arranque sin red), se usa el grupo
     * «Adultos» salvo Adult Swim, para que nunca se vean por adelantado.
     */
    static boolean isAdult(Channel channel) {
        if (channel == null) return false;
        String tvgId = channel.getTvgId() == null ? "" : channel.getTvgId().trim();
        Set<String> current = published;
        if (!current.isEmpty()) return current.contains(tvgId);
        String group = channel.getGroup() == null ? "" : channel.getGroup().trim();
        String name = channel.getName() == null ? "" : channel.getName().toLowerCase(Locale.ROOT);
        return FALLBACK_GROUP.equalsIgnoreCase(group) && !name.contains("adult swim");
    }

    static Set<String> parse(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_BYTES) return Collections.emptySet();
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schema", 0) != 1) return Collections.emptySet();
            JSONArray values = root.optJSONArray("channels");
            if (values == null) return Collections.emptySet();
            Set<String> result = new HashSet<>();
            for (int index = 0; index < values.length() && result.size() < 2000; index++) {
                String id = values.optString(index, "").trim();
                if (!id.isEmpty() && id.length() <= 512) result.add(id);
            }
            return Collections.unmodifiableSet(result);
        } catch (Exception invalid) {
            return Collections.emptySet();
        }
    }

    static boolean isUnlocked(Context context) {
        return prefs(context).getBoolean(KEY_UNLOCKED, false);
    }

    static void lock(Context context) {
        prefs(context).edit().putBoolean(KEY_UNLOCKED, false).apply();
    }

    /** Milisegundos que faltan para poder volver a intentar; 0 si ya se puede. */
    static long waitMillis(Context context, long nowMillis) {
        return Math.max(0L, prefs(context).getLong(KEY_LOCKED_UNTIL, 0L) - nowMillis);
    }

    /**
     * Prueba la clave y, si coincide, desbloquea. Es lenta a propósito (PBKDF2): llamar fuera
     * del hilo principal. Tras {@link #MAX_FAILURES} fallos seguidos, espera {@link #LOCKOUT_MS}.
     */
    static boolean tryUnlock(Context context, String pin, long nowMillis) {
        if (waitMillis(context, nowMillis) > 0) return false;
        SharedPreferences preferences = prefs(context);
        if (matches(pin, SALT_HEX, HASH_HEX, ITERATIONS)) {
            preferences.edit().putBoolean(KEY_UNLOCKED, true).putInt(KEY_FAILURES, 0)
                    .putLong(KEY_LOCKED_UNTIL, 0L).apply();
            return true;
        }
        int failures = preferences.getInt(KEY_FAILURES, 0) + 1;
        SharedPreferences.Editor editor = preferences.edit();
        if (failures >= MAX_FAILURES) {
            editor.putInt(KEY_FAILURES, 0).putLong(KEY_LOCKED_UNTIL, nowMillis + LOCKOUT_MS);
        } else {
            editor.putInt(KEY_FAILURES, failures);
        }
        editor.apply();
        return false;
    }

    static boolean matches(String pin, String saltHex, String hashHex, int iterations) {
        if (pin == null || pin.length() < MIN_DIGITS || pin.length() > MAX_DIGITS
                || !pin.matches("[0-9]+")) {
            return false;
        }
        try {
            byte[] expected = hex(hashHex);
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), hex(saltHex), iterations,
                    expected.length * 8);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            spec.clearPassword();
            return MessageDigest.isEqual(derived, expected);
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
