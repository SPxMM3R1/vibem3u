package cl.streambox.tv;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * Tarjeta de aviso sobre cualquier app (requiere "Mostrar sobre otras apps").
 * Se cierra sola a los {@link #AUTO_DISMISS_MS} ms; "Ver ahora" abre VibeM3U.
 */
final class ReminderOverlay {
    static final long AUTO_DISMISS_MS = 20_000L;
    private static final int MARGIN_DP = 32;

    private static View current;

    private ReminderOverlay() {
    }

    /** Muestra la tarjeta; devuelve false si falta el permiso o la tele la rechaza. */
    static boolean show(Context context, String title, String body) {
        Context app = context.getApplicationContext();
        if (!ReminderAlerts.canDrawOverlays(app)) return false;
        WindowManager windows = app.getSystemService(WindowManager.class);
        if (windows == null) return false;
        remove(windows);

        Context themed = new ContextThemeWrapper(app, R.style.Theme_VibeM3U);
        View card = LayoutInflater.from(themed).inflate(R.layout.reminder_overlay, null);
        ((TextView) card.findViewById(R.id.reminder_title)).setText(title);
        ((TextView) card.findViewById(R.id.reminder_body)).setText(body);
        Button watch = card.findViewById(R.id.reminder_watch);
        Button close = card.findViewById(R.id.reminder_close);

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable dismiss = () -> remove(windows);
        watch.setOnClickListener(view -> {
            handler.removeCallbacks(dismiss);
            remove(windows);
            Intent open = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(open);
        });
        close.setOnClickListener(view -> {
            handler.removeCallbacks(dismiss);
            remove(windows);
        });
        View.OnKeyListener backCloses = (view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(dismiss);
                remove(windows);
            }
            return true;
        };
        watch.setOnKeyListener(backCloses);
        close.setOnKeyListener(backCloses);

        int margin = Math.round(MARGIN_DP * app.getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = margin;
        params.y = margin;
        params.setTitle("VibeM3U aviso");
        try {
            windows.addView(card, params);
        } catch (RuntimeException error) {
            return false;
        }
        current = card;
        watch.requestFocus();
        handler.postDelayed(dismiss, AUTO_DISMISS_MS);
        return true;
    }

    private static void remove(WindowManager windows) {
        View card = current;
        current = null;
        if (card == null) return;
        try {
            windows.removeViewImmediate(card);
        } catch (RuntimeException ignored) {
            // Ya se había quitado.
        }
    }
}
