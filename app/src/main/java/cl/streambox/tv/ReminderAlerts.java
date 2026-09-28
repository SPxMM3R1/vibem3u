package cl.streambox.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Avisos programados con AlarmManager: llegan aunque la app esté cerrada.
 *
 * <p>Por ahora solo existe el aviso de prueba de Ajustes, que sirve para
 * comprobar en cada tele si la alarma llega con la app cerrada y si se puede
 * mostrar la tarjeta sobre otras apps.
 */
final class ReminderAlerts {
    static final String ACTION_TEST = "cl.streambox.tv.action.REMINDER_TEST";
    static final long TEST_DELAY_MS = 60_000L;
    static final String ADB_GRANT_COMMAND =
            "adb shell appops set cl.streambox.tv SYSTEM_ALERT_WINDOW allow";

    private static final String PREFS = "reminder_alerts";
    private static final String KEY_TEST_SCHEDULED_AT = "test_scheduled_at";
    private static final String KEY_TEST_EXACT = "test_exact";
    private static final String KEY_TEST_FIRED_AT = "test_fired_at";
    private static final String KEY_TEST_OVERLAY_SHOWN = "test_overlay_shown";
    private static final int TEST_REQUEST_CODE = 7101;

    private ReminderAlerts() {
    }

    static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /** Programa el aviso de prueba para dentro de un minuto. */
    static long scheduleTest(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) throw new IllegalStateException("AlarmManager no disponible");
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(ACTION_TEST);
        PendingIntent pending = PendingIntent.getBroadcast(
                context,
                TEST_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        long triggerAt = System.currentTimeMillis() + TEST_DELAY_MS;
        boolean exact = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
                exact = true;
            } catch (SecurityException denied) {
                // Permiso de alarma exacta retirado: se usa una aproximada.
            }
        }
        if (!exact) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
        prefs(context).edit()
                .putLong(KEY_TEST_SCHEDULED_AT, triggerAt)
                .putBoolean(KEY_TEST_EXACT, exact)
                .remove(KEY_TEST_FIRED_AT)
                .remove(KEY_TEST_OVERLAY_SHOWN)
                .apply();
        return triggerAt;
    }

    static void recordTestFired(Context context, boolean overlayShown) {
        prefs(context).edit()
                .putLong(KEY_TEST_FIRED_AT, System.currentTimeMillis())
                .putBoolean(KEY_TEST_OVERLAY_SHOWN, overlayShown)
                .apply();
    }

    /** Estado legible para Ajustes: permiso y resultado de la última prueba. */
    static String status(Context context) {
        StringBuilder text = new StringBuilder();
        text.append(canDrawOverlays(context)
                ? "Mostrar sobre otras apps: permitido."
                : "Mostrar sobre otras apps: no permitido.");
        SharedPreferences prefs = prefs(context);
        long scheduledAt = prefs.getLong(KEY_TEST_SCHEDULED_AT, 0L);
        if (scheduledAt <= 0L) return text.toString();
        long firedAt = prefs.getLong(KEY_TEST_FIRED_AT, 0L);
        text.append("\nÚltima prueba: ");
        if (firedAt > 0L) {
            text.append("llegó a las ").append(clock(firedAt, true));
            text.append(prefs.getBoolean(KEY_TEST_OVERLAY_SHOWN, false)
                    ? " y se mostró la tarjeta."
                    : ", pero sin permiso solo se mostró un mensaje breve.");
        } else if (System.currentTimeMillis() > scheduledAt + 120_000L) {
            text.append("no llegó (estaba programada para las ")
                    .append(clock(scheduledAt, false)).append(").");
        } else {
            text.append("programada para las ").append(clock(scheduledAt, false))
                    .append(". Sal de la app y espera.");
        }
        if (!prefs.getBoolean(KEY_TEST_EXACT, true)) {
            text.append(" (alarma aproximada: la tele no permite hora exacta)");
        }
        return text.toString();
    }

    private static String clock(long epochMs, boolean seconds) {
        return new SimpleDateFormat(seconds ? "HH:mm:ss" : "HH:mm", Locale.getDefault())
                .format(new Date(epochMs));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
