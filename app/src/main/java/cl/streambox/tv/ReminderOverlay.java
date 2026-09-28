package cl.streambox.tv;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Aviso compacto sobre cualquier app (requiere "Mostrar sobre otras apps"):
 * «Recordatorio · canal» con campana y el programa, con «Ver» a la derecha.
 * OK lleva al canal; Atrás o {@link #AUTO_DISMISS_MS} ms lo cierran.
 */
final class ReminderOverlay {
    static final long AUTO_DISMISS_MS = 15_000L;
    /** Área segura de TV. */
    private static final int MARGIN_SIDE_DP = 48;
    private static final int MARGIN_TOP_DP = 27;

    private static View current;

    private ReminderOverlay() {
    }

    /**
     * Muestra el aviso; devuelve false si falta el permiso o la tele lo rechaza.
     * {@code channelIdentity} null abre VibeM3U en el canal que tenía.
     */
    static boolean show(Context context, String channelName, String title, String channelIdentity) {
        Context app = context.getApplicationContext();
        if (!ReminderAlerts.canDrawOverlays(app)) return false;
        WindowManager windows = app.getSystemService(WindowManager.class);
        if (windows == null) return false;
        remove(windows);

        Context themed = new ContextThemeWrapper(app, R.style.Theme_VibeM3U);
        View card = LayoutInflater.from(themed).inflate(R.layout.reminder_overlay, null);
        ((TextView) card.findViewById(R.id.reminder_kicker)).setText(kicker(app, channelName));
        ((TextView) card.findViewById(R.id.reminder_title)).setText(title);
        View watch = card.findViewById(R.id.reminder_watch);

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable dismiss = () -> remove(windows);
        watch.setOnClickListener(view -> {
            handler.removeCallbacks(dismiss);
            remove(windows);
            // Por nombre: MainActivity usa API inestable de Media3 y no se referencia aquí.
            Intent open = new Intent()
                    .setClassName(app, "cl.streambox.tv.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            if (channelIdentity != null) {
                open.putExtra(ReminderAlerts.EXTRA_OPEN_CHANNEL_IDENTITY, channelIdentity);
            }
            app.startActivity(open);
        });
        watch.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(dismiss);
                remove(windows);
            }
            return true;
        });

        float density = app.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = Math.round(MARGIN_SIDE_DP * density);
        params.y = Math.round(MARGIN_TOP_DP * density);
        params.windowAnimations = android.R.style.Animation_Toast;
        params.setTitle("VibeM3U recordatorio");
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

    /** «Recordatorio» en cyan y « · canal» en gris; la campana va como drawable. */
    private static CharSequence kicker(Context context, String channelName) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        String label = context.getString(R.string.reminder_label);
        text.append(label);
        text.setSpan(new ForegroundColorSpan(context.getColor(R.color.cyan)),
                0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (channelName != null && !channelName.trim().isEmpty()) {
            int start = text.length();
            text.append(" · ").append(channelName.trim());
            text.setSpan(new ForegroundColorSpan(context.getColor(R.color.muted)),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
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
