package cl.streambox.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

/**
 * Fondo del OSD con el lenguaje de la Guía: oscuro abajo a la izquierda (donde va el
 * texto) y abriéndose hacia la derecha y hacia arriba para dejar ver el video. Es negro
 * puro: sobre la pantalla negra de carga no aclara nada (en teles mini-LED, un gris
 * azulado encendía las zonas de luz detrás del OSD).
 *
 * <p>Desde la 0.5.60 todas las transiciones siguen una curva suave (smoothstep en
 * {@link #STOPS} paradas) en vez de una rampa recta: la rampa recta dejaba una línea
 * visible donde empezaba la sombra. La hora lleva su propia sombra elíptica difusa.
 */
public final class OsdScrimView extends View {
    private static final int BASE = 0x000000;
    /** Altura de la sombra lateral y de la inferior (dp). */
    private static final float LEFT_HEIGHT_DP = 400f;
    private static final float BOTTOM_HEIGHT_DP = 190f;
    /** Parte de la sombra lateral en que el negro ya llegó a su máximo. */
    private static final float LEFT_FADE_PORTION = 0.6f;
    /** Sombra de la hora: radios de la elipse (dp) y opacidad en el centro. */
    private static final float CLOCK_RADIUS_X_DP = 300f;
    private static final float CLOCK_RADIUS_Y_DP = 150f;
    private static final float CLOCK_ALPHA = 0.78f;
    private static final int STOPS = 16;

    private final Paint leftPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bottomPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Matrix clockMatrix = new Matrix();
    private float clockCenterX = Float.NaN;
    private float clockCenterY = Float.NaN;

    public OsdScrimView(Context context) { this(context, null); }

    public OsdScrimView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private static int base(float alpha) {
        return (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | BASE;
    }

    /** Curva suave: pendiente cero al empezar y al terminar, sin línea visible. */
    static float smoothstep(float t) {
        float x = Math.max(0f, Math.min(1f, t));
        return x * x * (3f - 2f * x);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        float leftTop = height - dp(LEFT_HEIGHT_DP);
        // De izquierda a derecha, enmascarado para que se desvanezca hacia arriba.
        Shader horizontal = new LinearGradient(0f, 0f, width, 0f,
                new int[] {base(.96f), base(.90f), base(.55f), base(.25f)},
                new float[] {0f, .34f, .64f, 1f}, Shader.TileMode.CLAMP);
        int[] maskColors = new int[STOPS + 2];
        float[] maskStops = new float[STOPS + 2];
        for (int i = 0; i <= STOPS; i++) {
            float t = i / (float) STOPS;
            maskColors[i] = base(smoothstep(t)) | 0x00FFFFFF;
            maskStops[i] = t * LEFT_FADE_PORTION;
        }
        maskColors[STOPS + 1] = 0xFFFFFFFF;
        maskStops[STOPS + 1] = 1f;
        Shader fadeUp = new LinearGradient(0f, leftTop, 0f, height, maskColors, maskStops,
                Shader.TileMode.CLAMP);
        leftPaint.setShader(new ComposeShader(horizontal, fadeUp, PorterDuff.Mode.DST_IN));

        int[] bottomColors = new int[STOPS + 1];
        float[] bottomStops = new float[STOPS + 1];
        for (int i = 0; i <= STOPS; i++) {
            float t = i / (float) STOPS;
            bottomColors[i] = base(.92f * smoothstep(t));
            bottomStops[i] = t;
        }
        bottomPaint.setShader(new LinearGradient(0f, height - dp(BOTTOM_HEIGHT_DP), 0f, height,
                bottomColors, bottomStops, Shader.TileMode.CLAMP));

        // Sombra de la hora: círculo de radio Y, estirado a elipse con la matriz.
        int[] clockColors = new int[STOPS + 1];
        float[] clockStops = new float[STOPS + 1];
        for (int i = 0; i <= STOPS; i++) {
            float t = i / (float) STOPS;
            clockColors[i] = base(CLOCK_ALPHA * smoothstep(1f - t));
            clockStops[i] = t;
        }
        clockPaint.setShader(new RadialGradient(0f, 0f, dp(CLOCK_RADIUS_Y_DP), clockColors,
                clockStops, Shader.TileMode.CLAMP));
        clockCenterX = Float.NaN;
    }

    /** Centro de la hora (hermana en el mismo contenedor), o NaN si no está visible. */
    private boolean updateClockCenter() {
        if (!(getParent() instanceof ViewGroup)) return false;
        View clock = ((ViewGroup) getParent()).findViewById(R.id.osd_clock);
        if (clock == null || clock.getVisibility() != View.VISIBLE || clock.getWidth() <= 0) {
            return false;
        }
        float x = clock.getLeft() + clock.getWidth() / 2f - getLeft();
        float y = clock.getTop() + clock.getHeight() / 2f - getTop();
        if (x != clockCenterX || y != clockCenterY) {
            clockCenterX = x;
            clockCenterY = y;
            clockMatrix.setScale(CLOCK_RADIUS_X_DP / CLOCK_RADIUS_Y_DP, 1f);
            clockMatrix.postTranslate(x, y);
            clockPaint.getShader().setLocalMatrix(clockMatrix);
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.drawRect(0f, height - dp(LEFT_HEIGHT_DP), width, height, leftPaint);
        canvas.drawRect(0f, height - dp(BOTTOM_HEIGHT_DP), width, height, bottomPaint);
        if (clockPaint.getShader() != null && updateClockCenter()) {
            float rx = dp(CLOCK_RADIUS_X_DP);
            float ry = dp(CLOCK_RADIUS_Y_DP);
            canvas.drawRect(clockCenterX - rx, clockCenterY - ry,
                    clockCenterX + rx, clockCenterY + ry, clockPaint);
        }
    }
}
