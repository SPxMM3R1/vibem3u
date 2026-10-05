package cl.streambox.tv;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Diálogos «escena» (2026-10-03): ocupan la pantalla sobre el video, sin tarjeta ni
 * atenuado del sistema. El velo y el degradado del OSD vienen en el propio layout.
 */
final class SceneDialog {
    private SceneDialog() {}

    static Dialog create(Activity activity, int layout) {
        Dialog dialog = new Dialog(activity, R.style.Theme_VibeM3U_Scene);
        dialog.setContentView(layout);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return dialog;
    }

    /**
     * Oculta {@code host} (p. ej. el menú de Opciones, que es translúcido sobre el video)
     * mientras la escena está abierta, para que detrás solo quede el video. Llamar después de
     * {@code show()}. Varias escenas encadenadas comparten un contador: el menú vuelve al
     * cerrarse la última.
     */
    static void hideHostWhileShown(Dialog dialog, View host) {
        Window window = dialog.getWindow();
        if (host == null || window == null || !dialog.isShowing()) return;
        View decor = window.getDecorView();
        Object tag = host.getTag(R.id.scene_host_hidden_count);
        int count = tag instanceof Integer ? (Integer) tag : 0;
        host.setTag(R.id.scene_host_hidden_count, count + 1);
        host.setVisibility(View.INVISIBLE);
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}

            @Override public void onViewDetachedFromWindow(View view) {
                decor.removeOnAttachStateChangeListener(this);
                Object current = host.getTag(R.id.scene_host_hidden_count);
                int left = (current instanceof Integer ? (Integer) current : 1) - 1;
                host.setTag(R.id.scene_host_hidden_count, Math.max(0, left));
                if (left <= 0) host.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Oculta las barras del sistema también en la ventana del diálogo. */
    static void hideSystemBars(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
