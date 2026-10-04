package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Ajustes a mano del tamaño de cada logo, guardados en la TV (clave: dirección del logo). */
final class LogoScales {
    static final String PREFS = "logo_scales";

    private LogoScales() {}

    /** Carga los ajustes guardados en {@link LogoFit}. */
    static void load(Context context) {
        Map<String, Float> scales = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getValue() instanceof Float) scales.put(entry.getKey(), (Float) entry.getValue());
        }
        LogoFit.replaceUserScales(scales);
    }

    static float get(Context context, String logoKey) {
        if (logoKey == null) return 1f;
        return prefs(context).getFloat(logoKey, 1f);
    }

    /** Guarda el ajuste (1 lo borra) y lo deja activo en {@link LogoFit}. */
    static void save(Context context, String logoKey, float scale) {
        if (logoKey == null) return;
        LogoFit.setUserScale(logoKey, scale);
        float stored = LogoFit.userScale(logoKey);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (Math.abs(stored - 1f) < 0.001f) editor.remove(logoKey);
        else editor.putFloat(logoKey, stored);
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
