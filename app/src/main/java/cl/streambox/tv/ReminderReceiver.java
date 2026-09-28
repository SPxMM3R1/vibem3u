package cl.streambox.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Recibe la alarma de un aviso; Android abre el proceso aunque la app esté cerrada. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ReminderAlerts.ACTION_TEST.equals(intent.getAction())) return;
        boolean shown = ReminderOverlay.show(
                context,
                context.getString(R.string.reminder_test_title),
                context.getString(R.string.reminder_test_body)
        );
        if (!shown) {
            Toast.makeText(
                    context.getApplicationContext(),
                    R.string.reminder_test_toast,
                    Toast.LENGTH_LONG
            ).show();
        }
        ReminderAlerts.recordTestFired(context, shown);
    }
}
