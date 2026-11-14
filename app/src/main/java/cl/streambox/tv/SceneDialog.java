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
            // «Flotante minimalista» (0.5.98): la acción con foco va en negrita.
            window.getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                    (oldFocus, newFocus) -> {
                        setBold(oldFocus, false);
                        setBold(newFocus, true);
                        // Animaciones B (2026-11-14): la acción marcada crece con resorte.
                        if (oldFocus instanceof android.widget.Button) Motion.focusScale(oldFocus, false, Motion.FOCUS_SCALE);
                        if (newFocus instanceof android.widget.Button) Motion.focusScale(newFocus, true, Motion.FOCUS_SCALE);
                    });
            View decor = window.getDecorView();
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    decor.removeOnAttachStateChangeListener(this);
                    View content = dialog.findViewById(android.R.id.content);
                    if (content instanceof android.view.ViewGroup) {
                        android.view.ViewGroup frame = (android.view.ViewGroup) content;
                        if (frame.getChildCount() > 0) animateIn(frame.getChildAt(0));
                    }
                }

                @Override public void onViewDetachedFromWindow(View view) {}
            });
        }
        return dialog;
    }

    /**
     * Entrada de una escena: los velos se funden y el texto y las acciones suben escalonados.
     * Las escenas son del estilo moderno (dialog_scene y sus variantes).
     */
    static void animateIn(View root) {
        if (!(root instanceof android.view.ViewGroup)) {
            Motion.enter(root, 0, 0f);
            return;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        float rise = 14f * root.getResources().getDisplayMetrics().density;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof android.view.ViewGroup) {
                Motion.staggerChildren((android.view.ViewGroup) child, rise);
            } else {
                child.setAlpha(0f);
                Motion.fadeTo(child, 1f, 400L, null);
            }
        }
    }

    /** Negrita en la acción con foco (Button); el resto de los textos no cambia. */
    static void setBold(View view, boolean bold) {
        if (!(view instanceof android.widget.Button)) return;
        ((android.widget.Button) view).setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, bold ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL));
    }

    /** Lo mismo para acciones fuera de una escena (aviso de calidad, MediaFlow). */
    static void boldWhenFocused(View... views) {
        for (View view : views) {
            if (view == null) continue;
            View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
            view.setOnFocusChangeListener((v, focused) -> {
                setBold(v, focused);
                if (previous != null) previous.onFocusChange(v, focused);
            });
            setBold(view, view.isFocused());
        }
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
