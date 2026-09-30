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
    private static final String KEY_CLASSIC_STARTING = "classic_ui_starting";

    private UiStyle() {}

    static boolean isClassic(Context context) {
        return prefs(context).getBoolean(KEY_CLASSIC, false);
    }

    static void setClassic(Context context, boolean classic) {
        prefs(context).edit().putBoolean(KEY_CLASSIC, classic).apply();
    }

    /**
     * Estilo con que arranca la pantalla principal. Si el arranque anterior en estilo clásico
     * no terminó (la app se cerró al preparar la pantalla), vuelve al moderno: el estilo nunca
     * debe impedir que la app abra.
     */
    static boolean beginStart(Context context) {
        SharedPreferences prefs = prefs(context);
        boolean classic = prefs.getBoolean(KEY_CLASSIC, false);
        if (classic && prefs.getBoolean(KEY_CLASSIC_STARTING, false)) {
            prefs.edit().putBoolean(KEY_CLASSIC, false).putBoolean(KEY_CLASSIC_STARTING, false).commit();
            return false;
        }
        if (classic) prefs.edit().putBoolean(KEY_CLASSIC_STARTING, true).commit();
        return classic;
    }

    /** La pantalla principal terminó de prepararse con el estilo elegido. */
    static void startCompleted(Context context) {
        prefs(context).edit().putBoolean(KEY_CLASSIC_STARTING, false).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
