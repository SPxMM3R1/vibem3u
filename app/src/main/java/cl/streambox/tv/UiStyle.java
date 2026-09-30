package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Estilo de la interfaz elegido en Opciones › Interfaz: moderno (por defecto) o clásico
 * (diseño de la 0.5.40 en la Guía, el OSD y los menús). Las pantallas lo leen al crearse;
 * al cambiarlo se recargan.
 */
final class UiStyle {
    private static final String PREFS = "interface";
    private static final String KEY_CLASSIC = "classic_ui";

    private UiStyle() {}

    static boolean isClassic(Context context) {
        return prefs(context).getBoolean(KEY_CLASSIC, false);
    }

    static void setClassic(Context context, boolean classic) {
        prefs(context).edit().putBoolean(KEY_CLASSIC, classic).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
