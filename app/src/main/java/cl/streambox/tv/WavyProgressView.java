package cl.streambox.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Indicador de carga ondulado (Material 3 Expressive), sin pista recta: una onda cyan que
 * avanza, se estira y se recoge, con los extremos difuminados como el título largo del OSD.
 * Solo anima mientras está en pantalla; es una vista chica que se usa durante la carga.
 *
 * <p>Cada vuelta entra por la izquierda vacía y sale por la derecha vacía: la cabeza y la
 * cola recorren la vista con la misma curva, la cola con retraso, así el largo crece al
 * entrar y se recoge al salir (como el indicador indeterminado de Material). El reloj se
 * reinicia cada vez que la vista aparece, para que nunca empiece a mitad de camino.
 */
public final class WavyProgressView extends View {
    private static final int CYAN = 0xFF00B8E6;
    private static final int CYAN_CLEAR = 0x0000B8E6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    /** Duración de una vuelta (ms). */
    private static final float CYCLE_MS = 1700f;
    /** Cuánto se atrasa la cola respecto de la cabeza (fracción de la vuelta). */
    private static final float TAIL_DELAY = 0.3f;

    private long startUptime = SystemClock.uptimeMillis();
    private boolean wasVisible;

    public WavyProgressView(Context context) { this(context, null); }

    public WavyProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(2.5f));
        setWillNotDraw(false);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Extremos difuminados: la onda aparece y desaparece en los bordes.
        paint.setShader(new LinearGradient(0f, 0f, width, 0f,
                new int[] {CYAN_CLEAR, CYAN, CYAN, CYAN_CLEAR},
                new float[] {0f, 0.22f, 0.78f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;
        long elapsed = SystemClock.uptimeMillis() - startUptime;
        float t = elapsed / 1000f;
        float phase = (elapsed % (long) CYCLE_MS) / CYCLE_MS;
        float head = ease(Math.min(1f, phase / (1f - TAIL_DELAY)));
        float tail = ease(Math.max(0f, (phase - TAIL_DELAY) / (1f - TAIL_DELAY)));
        float from = tail * width;
        float to = head * width;
        float amplitude = Math.min(dp(3f), height / 2f - paint.getStrokeWidth());
        float middle = height / 2f;
        path.reset();
        boolean first = true;
        for (float x = from; x <= to; x += dp(2f)) {
            float y = middle + amplitude * (float) Math.sin(x / width * Math.PI * 12 - t * 8);
            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }
        if (!first) canvas.drawPath(path, paint);
        if (isShown()) postInvalidateOnAnimation();
    }

    /** Curva suave al partir y al llegar (cúbica de entrada y salida). */
    private static float ease(float x) {
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && !wasVisible) startUptime = SystemClock.uptimeMillis();
        wasVisible = isVisible;
        if (isVisible) postInvalidateOnAnimation();
    }
}
