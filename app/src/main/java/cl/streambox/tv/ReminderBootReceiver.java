package cl.streambox.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android borra las alarmas al reiniciar la tele o actualizar la app: aquí se
 * vuelven a programar. Solo reprograma lo guardado; no acepta datos del intent.
 */
public final class ReminderBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ReminderAlerts.rescheduleAll(context);
        }
    }
}
