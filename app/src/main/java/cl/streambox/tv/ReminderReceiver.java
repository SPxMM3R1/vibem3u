package cl.streambox.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Recibe la alarma de un recordatorio; Android abre el proceso aunque la app esté cerrada. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ReminderAlerts.ACTION_TEST.equals(action)) {
            boolean shown = ReminderOverlay.show(
                    context,
                    context.getString(R.string.app_name),
                    context.getString(R.string.reminder_test_title),
                    null
            );
            if (!shown) toast(context, context.getString(R.string.reminder_test_toast));
            ReminderAlerts.recordTestFired(context, shown);
            return;
        }
        if (!ReminderAlerts.ACTION_REMINDER.equals(action)) return;
        String id = intent.getStringExtra(ReminderAlerts.EXTRA_REMINDER_ID);
        if (id == null) return;
        ProgramReminder reminder = ReminderAlerts.take(context, id);
        // Si la tele estuvo apagada y el programa ya terminó, no se avisa.
        if (reminder == null || reminder.stopMillis <= System.currentTimeMillis()) return;
        boolean shown = ReminderOverlay.show(
                context,
                reminder.channelName,
                reminder.title,
                reminder.channelIdentity
        );
        if (!shown) {
            toast(context, context.getString(
                    R.string.reminder_fallback_toast, reminder.title, reminder.channelName));
        }
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }
}
