package cl.streambox.tv;

import android.app.Activity;
import android.app.Dialog;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** Escenas de Opciones › Canales › Canales de adultos (0.6.36): desbloquear y bloquear. */
final class AdultChannelScenes {
    private AdultChannelScenes() {}

    /**
     * Teclado de la clave. Acepta las teclas en pantalla y los números del mando. Si la clave
     * coincide, cierra y corre {@code onUnlocked} en el hilo principal.
     */
    static Dialog showUnlock(Activity activity, Runnable onUnlocked) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_adult_unlock);
        TextView dots = dialog.findViewById(R.id.adult_pin_dots);
        TextView status = dialog.findViewById(R.id.adult_pin_status);
        GridLayout pad = dialog.findViewById(R.id.adult_keypad);
        Button cancel = dialog.findViewById(R.id.adult_cancel);
        StringBuilder pin = new StringBuilder();
        boolean[] checking = {false};
        View[] center = {null};
        float density = activity.getResources().getDisplayMetrics().density;

        Runnable draw = () -> {
            StringBuilder masked = new StringBuilder();
            for (int index = 0; index < pin.length(); index++) {
                if (index > 0) masked.append(' ');
                masked.append('•');
            }
            dots.setText(masked);
        };
        Consumer<String> press = key -> {
            if (checking[0]) return;
            status.setText("");
            if ("del".equals(key)) {
                if (pin.length() > 0) pin.setLength(pin.length() - 1);
            } else if ("ok".equals(key)) {
                long wait = AdultChannels.waitMillis(activity, System.currentTimeMillis());
                if (wait > 0) {
                    status.setText(activity.getString(R.string.adult_unlock_wait, (wait + 999) / 1000));
                    return;
                }
                if (pin.length() < AdultChannels.MIN_DIGITS) {
                    status.setText(R.string.adult_unlock_short);
                    return;
                }
                checking[0] = true;
                status.setText(R.string.adult_unlock_checking);
                String attempt = pin.toString();
                new Thread(() -> {
                    long now = System.currentTimeMillis();
                    boolean unlocked = AdultChannels.tryUnlock(activity, attempt, now);
                    long blocked = AdultChannels.waitMillis(activity, now);
                    activity.runOnUiThread(() -> {
                        checking[0] = false;
                        if (!dialog.isShowing()) return;
                        if (unlocked) {
                            dialog.dismiss();
                            onUnlocked.run();
                            return;
                        }
                        pin.setLength(0);
                        draw.run();
                        status.setText(blocked > 0
                                ? activity.getString(R.string.adult_unlock_wait, (blocked + 999) / 1000)
                                : activity.getString(R.string.adult_unlock_wrong));
                    });
                }, "adult-unlock").start();
            } else if (pin.length() < AdultChannels.MAX_DIGITS) {
                pin.append(key);
            }
            draw.run();
        };

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "del", "0", "ok"};
        int margin = Math.round(3 * density);
        for (String key : keys) {
            Button button = new Button(activity);
            button.setText("del".equals(key) ? activity.getString(R.string.adult_key_delete)
                    : "ok".equals(key) ? activity.getString(R.string.adult_key_ok) : key);
            button.setAllCaps(false);
            button.setBackgroundResource(R.drawable.adult_key);
            button.setTextColor(activity.getColorStateList(R.color.adult_key_text));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, key.length() > 1 ? 10f : 14f);
            button.setStateListAnimator(null);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setMinimumWidth(0);
            button.setMinimumHeight(0);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(view -> press.accept(key));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = Math.round(52 * density);
            params.height = Math.round(34 * density);
            params.setMargins(margin, margin, margin, margin);
            pad.addView(button, params);
            if ("5".equals(key)) center[0] = button;
        }
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                press.accept(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                press.accept("del");
                return true;
            }
            return false;
        });
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> {
            if (center[0] != null) center[0].requestFocus();
        });
        draw.run();
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        return dialog;
    }

    /** Confirmación de «Bloquear»: los canales se ocultan de inmediato. */
    static Dialog showLockConfirm(Activity activity, Runnable onConfirm) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_scene);
        ((TextView) dialog.findViewById(R.id.scene_kicker)).setText(R.string.adult_channels_section);
        ((TextView) dialog.findViewById(R.id.scene_title)).setText(R.string.adult_lock_title);
        ((TextView) dialog.findViewById(R.id.scene_message)).setText(R.string.adult_lock_message);
        Button primary = dialog.findViewById(R.id.scene_primary);
        Button secondary = dialog.findViewById(R.id.scene_secondary);
        primary.setText(R.string.adult_channels_lock);
        secondary.setText(R.string.cancel);
        primary.setOnClickListener(view -> {
            dialog.dismiss();
            onConfirm.run();
        });
        secondary.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> secondary.requestFocus());
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        return dialog;
    }
}
