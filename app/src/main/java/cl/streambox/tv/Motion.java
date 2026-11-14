package cl.streambox.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Movimiento «B · Apple TV» (2026-11-14, mockup aprobado: «Aplica animaciones B para todo»):
 * resorte con un rebote leve, información que entra escalonada, foco que viaja y crece, y un
 * rebote corto al chocar con el borde. Solo se anima alfa, traslación y escala (GPU); el video
 * ({@code SurfaceView}) nunca se anima. Respeta la escala de animaciones del sistema: con las
 * animaciones apagadas, Android termina cada animación al instante.
 */
final class Motion {
    private Motion() {}

    /** Resorte con rebote leve: entradas y foco. */
    static final Interpolator SPRING = new PathInterpolator(0.34f, 1.42f, 0.64f, 1f);
    /** Resorte más suave: líneas y marcos que viajan. */
    static final Interpolator SPRING_SOFT = new PathInterpolator(0.34f, 1.3f, 0.64f, 1f);
    /** Arranca rápido y frena largo: velos y fundidos de entrada. */
    static final Interpolator EXPO_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    static final Interpolator EASE_OUT = new PathInterpolator(0f, 0f, 0.2f, 1f);
    static final Interpolator EASE_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);

    static final long ENTER_MS = 620L;
    static final long FADE_IN_MS = 350L;
    static final long STAGGER_MS = 55L;
    static final long EXIT_MS = 220L;
    static final long GLIDE_MS = 420L;
    static final long SCALE_MS = 350L;
    static final long BUMP_MS = 320L;

    /** Escala del texto con foco (opciones, pestañas, acciones). */
    static final float FOCUS_SCALE = 1.07f;

    private static final WeakHashMap<View, Animator> RUNNING = new WeakHashMap<>();

    private static void run(View view, Animator animator) {
        cancel(view);
        RUNNING.put(view, animator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (RUNNING.get(view) == animation) RUNNING.remove(view);
            }
        });
        animator.start();
    }

    /** Detiene la entrada o salida en curso de {@code view} (no toca su escala de foco). */
    static void cancel(View view) {
        Animator running = RUNNING.remove(view);
        if (running != null) running.cancel();
    }

    /**
     * Entra subiendo {@code risePx} con resorte mientras se funde; {@code index} escalona
     * (55 ms por posición).
     */
    static void enter(View view, int index, float risePx) {
        if (view == null) return;
        cancel(view);
        view.setAlpha(0f);
        view.setTranslationY(risePx);
        ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
        fade.setDuration(FADE_IN_MS);
        fade.setInterpolator(EASE_OUT);
        ObjectAnimator rise = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, risePx, 0f);
        rise.setDuration(ENTER_MS);
        rise.setInterpolator(SPRING);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(fade, rise);
        set.setStartDelay(Math.max(0, index) * STAGGER_MS);
        run(view, set);
    }

    /** Entra desde un costado ({@code fromPx} en X) con resorte mientras se funde. */
    static void enterFromSide(View view, long delayMs, float fromPx) {
        if (view == null) return;
        cancel(view);
        view.setAlpha(0f);
        view.setTranslationX(fromPx);
        ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
        fade.setDuration(FADE_IN_MS + 50L);
        fade.setInterpolator(EASE_OUT);
        ObjectAnimator slide = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, fromPx, 0f);
        slide.setDuration(ENTER_MS);
        slide.setInterpolator(SPRING_SOFT);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(fade, slide);
        set.setStartDelay(Math.max(0L, delayMs));
        run(view, set);
    }

    /** Entrada escalonada de una lista de vistas (solo las visibles cuentan para el orden). */
    static void staggerIn(List<View> views, float risePx) {
        int index = 0;
        for (View view : views) {
            if (view == null || view.getVisibility() != View.VISIBLE) continue;
            enter(view, index++, risePx);
        }
    }

    /** Hijos visibles de {@code group}, en orden, entran escalonados. */
    static void staggerChildren(ViewGroup group, float risePx) {
        if (group == null) return;
        int index = 0;
        for (int child = 0; child < group.getChildCount(); child++) {
            View view = group.getChildAt(child);
            if (view.getVisibility() != View.VISIBLE) continue;
            enter(view, index++, risePx);
        }
    }

    /** Baja {@code dropPx} y se funde en 220 ms; al terminar corre {@code end} (si no se canceló). */
    static void exit(View view, float dropPx, Runnable end) {
        if (view == null) {
            if (end != null) end.run();
            return;
        }
        cancel(view);
        ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, view.getAlpha(), 0f);
        ObjectAnimator drop = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.getTranslationY(), dropPx);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(fade, drop);
        set.setDuration(EXIT_MS);
        set.setInterpolator(EASE_IN);
        set.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;
            @Override public void onAnimationCancel(Animator animation) { canceled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!canceled && end != null) end.run();
            }
        });
        run(view, set);
    }

    /** Deja la vista en reposo (sin traslación y opaca), cortando lo que estuviera animando. */
    static void settle(View view) {
        if (view == null) return;
        cancel(view);
        view.setAlpha(1f);
        view.setTranslationY(0f);
        view.setTranslationX(0f);
    }

    /** Fundido simple con la curva de entrada. */
    static void fadeTo(View view, float alpha, long durationMs, Runnable end) {
        if (view == null) return;
        cancel(view);
        ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, view.getAlpha(), alpha);
        fade.setDuration(durationMs);
        fade.setInterpolator(alpha > view.getAlpha() ? EASE_OUT : EXPO_OUT);
        if (end != null) {
            fade.addListener(new AnimatorListenerAdapter() {
                private boolean canceled;
                @Override public void onAnimationCancel(Animator animation) { canceled = true; }
                @Override public void onAnimationEnd(Animator animation) { if (!canceled) end.run(); }
            });
        }
        run(view, fade);
    }

    /**
     * El texto con foco crece con resorte; al perderlo vuelve. El pivote queda a la izquierda
     * (o a la derecha en textos alineados a la derecha) para que no se corra el borde de lectura.
     */
    static void focusScale(View view, boolean focused, float scale) {
        if (view == null) return;
        // Lo que está en la mitad derecha de la pantalla crece hacia la izquierda: si no, la
        // última acción («Papelera») se salía por el borde.
        int[] location = new int[2];
        view.getLocationInWindow(location);
        int screen = view.getRootView().getWidth();
        boolean rightSide = screen > 0 && location[0] + view.getWidth() / 2f > screen / 2f;
        boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        view.setPivotX(rightSide != rtl ? view.getWidth() : 0f);
        view.setPivotY(view.getHeight() / 2f);
        // El texto que crece se dibuja fuera de su caja: sus contenedores no lo recortan (hasta
        // cinco niveles, sin pasar de una lista con desplazamiento, que sí debe recortar).
        android.view.ViewParent parent = view.getParent();
        for (int level = 0; level < 5 && parent instanceof ViewGroup; level++) {
            if (parent instanceof android.widget.ScrollView
                    || parent instanceof android.widget.HorizontalScrollView) break;
            ((ViewGroup) parent).setClipChildren(false);
            parent = parent.getParent();
        }
        float target = focused ? scale : 1f;
        view.animate().scaleX(target).scaleY(target)
                .setDuration(SCALE_MS).setInterpolator(SPRING).start();
    }

    /** Rebote corto hacia {@code dx}/{@code dy} (en px): «no hay más hacia allá». */
    static void bump(View view, float dx, float dy) {
        if (view == null) return;
        ObjectAnimator x = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, dx, 0f);
        ObjectAnimator y = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, dy, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(x, y);
        set.setDuration(BUMP_MS);
        set.setInterpolator(EASE_OUT);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animation) {
                view.setTranslationX(0f);
                view.setTranslationY(0f);
            }
        });
        run(view, set);
    }

    // ---- Línea de foco que viaja (FLIP) ----

    private static WeakReference<View> lastLine = new WeakReference<>(null);
    private static final int[] lastLineLocation = new int[2];
    private static int lastLineWidth;
    private static long lastLineAt;

    /**
     * Línea fina de foco (DISENO.md): en vez de saltar, la nueva parte desde donde estaba la
     * anterior y llega con resorte; si no hay anterior cercana, crece desde su centro.
     * Android avisa primero a la que pierde el foco y después a la que lo gana.
     */
    static void focusLine(View line, boolean focused) {
        if (line == null) return;
        if (!focused) {
            if (line.getVisibility() == View.VISIBLE && line.isAttachedToWindow() && line.getWidth() > 0) {
                line.getLocationInWindow(lastLineLocation);
                lastLineLocation[0] -= Math.round(line.getTranslationX());
                lastLineLocation[1] -= Math.round(line.getTranslationY());
                lastLineWidth = Math.round(line.getWidth() * line.getScaleX());
                lastLine = new WeakReference<>(line);
                lastLineAt = SystemClock.uptimeMillis();
            }
            line.animate().cancel();
            line.setVisibility(View.INVISIBLE);
            line.setTranslationX(0f);
            line.setTranslationY(0f);
            line.setScaleX(1f);
            return;
        }
        line.setVisibility(View.VISIBLE);
        View previous = lastLine.get();
        boolean recent = SystemClock.uptimeMillis() - lastLineAt < 250L;
        lastLine = new WeakReference<>(null);
        if (line.getWidth() == 0) {
            line.post(() -> glideFrom(line, previous, recent));
            return;
        }
        glideFrom(line, previous, recent);
    }

    private static void glideFrom(View line, View previous, boolean recent) {
        if (!line.isAttachedToWindow() || line.getVisibility() != View.VISIBLE || line.getWidth() == 0) return;
        line.animate().cancel();
        line.setPivotX(0f);
        int[] here = new int[2];
        line.getLocationInWindow(here);
        boolean sameWindow = previous != null && previous != line
                && previous.getRootView() == line.getRootView();
        float dx = lastLineLocation[0] - here[0];
        float dy = lastLineLocation[1] - here[1];
        // Lejos (otro grupo, otra fila de pantalla) se ve mejor creciendo en su lugar.
        float far = line.getRootView().getWidth() * 0.45f;
        if (recent && sameWindow && Math.abs(dx) < far && Math.abs(dy) < far && lastLineWidth > 0) {
            line.setTranslationX(dx);
            line.setTranslationY(dy);
            line.setScaleX(lastLineWidth / (float) line.getWidth());
            line.animate().translationX(0f).translationY(0f).scaleX(1f)
                    .setDuration(GLIDE_MS).setInterpolator(SPRING_SOFT).start();
        } else {
            line.setPivotX(line.getWidth() / 2f);
            line.setScaleX(0f);
            line.setTranslationX(0f);
            line.setTranslationY(0f);
            line.animate().scaleX(1f).setDuration(GLIDE_MS).setInterpolator(SPRING_SOFT).start();
        }
    }
}
