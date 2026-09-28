package cl.streambox.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Recordatorios de programas con AlarmManager: el aviso llega aunque la app
 * esté cerrada. Se guardan en SharedPreferences y se reprograman al encender
 * la tele o actualizar la app.
 */
final class ReminderAlerts {
    static final String ACTION_TEST = "cl.streambox.tv.action.REMINDER_TEST";
    static final String ACTION_REMINDER = "cl.streambox.tv.action.PROGRAM_REMINDER";
    static final String EXTRA_REMINDER_ID = "cl.streambox.tv.extra.REMINDER_ID";
    /** MainActivity abre este canal al recibirlo (botón «Ver» del aviso). */
    static final String EXTRA_OPEN_CHANNEL_IDENTITY = "cl.streambox.tv.extra.OPEN_CHANNEL_IDENTITY";
    static final long TEST_DELAY_MS = 60_000L;
    static final String ADB_GRANT_COMMAND =
            "adb shell appops set cl.streambox.tv SYSTEM_ALERT_WINDOW allow";

    private static final String PREFS = "reminder_alerts";
    private static final String KEY_REMINDERS = "program_reminders";
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

    // ---- Recordatorios de programas -------------------------------------

    /** Recordatorios vigentes, ordenados por hora. Descarta los vencidos. */
    static synchronized List<ProgramReminder> list(Context context) {
        List<ProgramReminder> stored = ProgramReminder.decode(
                prefs(context).getString(KEY_REMINDERS, ""));
        List<ProgramReminder> upcoming = ProgramReminder.upcoming(stored, System.currentTimeMillis());
        if (upcoming.size() != stored.size()) save(context, upcoming);
        return upcoming;
    }

    /** Agrega o quita el recordatorio; devuelve true si quedó programado. */
    static synchronized boolean toggle(Context context, ProgramReminder reminder) {
        List<ProgramReminder> reminders = list(context);
        boolean added = ProgramReminder.toggle(reminders, reminder);
        save(context, reminders);
        if (added) {
            schedule(context, reminder);
        } else {
            cancel(context, reminder.id());
        }
        return added;
    }

    static synchronized void remove(Context context, String id) {
        List<ProgramReminder> reminders = list(context);
        ProgramReminder existing = ProgramReminder.find(reminders, id);
        if (existing == null) return;
        reminders.remove(existing);
        save(context, reminders);
        cancel(context, id);
    }

    /** Saca el recordatorio que acaba de sonar y lo devuelve (null si ya no existe). */
    static synchronized ProgramReminder take(Context context, String id) {
        List<ProgramReminder> reminders = ProgramReminder.decode(
                prefs(context).getString(KEY_REMINDERS, ""));
        ProgramReminder reminder = ProgramReminder.find(reminders, id);
        if (reminder != null) {
            reminders.remove(reminder);
            save(context, ProgramReminder.upcoming(reminders, System.currentTimeMillis()));
        }
        return reminder;
    }

    /** Vuelve a programar todas las alarmas (arranque de la tele, actualización o app abierta). */
    static synchronized void rescheduleAll(Context context) {
        for (ProgramReminder reminder : list(context)) schedule(context, reminder);
    }

    private static void save(Context context, List<ProgramReminder> reminders) {
        prefs(context).edit().putString(KEY_REMINDERS, ProgramReminder.encode(reminders)).apply();
    }

    private static void schedule(Context context, ProgramReminder reminder) {
        long triggerAt = Math.max(reminder.startMillis, System.currentTimeMillis() + 1_000L);
        scheduleAt(context, triggerAt, reminderIntent(context, reminder.id(), true));
    }

    private static void cancel(Context context, String id) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent pending = reminderIntent(context, id, false);
        if (alarms != null && pending != null) {
            alarms.cancel(pending);
            pending.cancel();
        }
    }

    private static PendingIntent reminderIntent(Context context, String id, boolean create) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_REMINDER)
                // El data distingue cada alarma; el id completo va como extra.
                .setData(Uri.parse("vibem3u-reminder://" + Integer.toHexString(id.hashCode())))
                .putExtra(EXTRA_REMINDER_ID, id);
        int flags = PendingIntent.FLAG_IMMUTABLE
                | (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags);
    }

    /** Alarma exacta si la tele lo permite; si no, una aproximada. Devuelve si fue exacta. */
    private static boolean scheduleAt(Context context, long triggerAt, PendingIntent pending) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) throw new IllegalStateException("AlarmManager no disponible");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
                return true;
            } catch (SecurityException denied) {
                // Permiso de alarma exacta retirado: se usa una aproximada.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        return false;
    }

    /** "Sáb 28 · 09:00" para listas y confirmaciones. */
    static String when(long millis) {
        String text = new SimpleDateFormat("EEE d · HH:mm", Locale.forLanguageTag("es-CL"))
                .format(new Date(millis)).replace(".", "");
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---- Aviso de prueba (Ajustes) -----------------------------------------

    /** Programa el aviso de prueba para dentro de un minuto. */
    static long scheduleTest(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(ACTION_TEST);
        PendingIntent pending = PendingIntent.getBroadcast(
                context,
                TEST_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        long triggerAt = System.currentTimeMillis() + TEST_DELAY_MS;
        boolean exact = scheduleAt(context, triggerAt, pending);
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
                : "Mostrar sobre otras apps: no permitido (el aviso no se verá con la app cerrada).");
        SharedPreferences prefs = prefs(context);
        long scheduledAt = prefs.getLong(KEY_TEST_SCHEDULED_AT, 0L);
        if (scheduledAt <= 0L) return text.toString();
        long firedAt = prefs.getLong(KEY_TEST_FIRED_AT, 0L);
        text.append("\nÚltima prueba: ");
        if (firedAt > 0L) {
            text.append("llegó a las ").append(clock(firedAt, true));
            text.append(prefs.getBoolean(KEY_TEST_OVERLAY_SHOWN, false)
                    ? " y se mostró el aviso."
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
