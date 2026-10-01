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
 */
public final class WavyProgressView extends View {
    private static final int CYAN = 0xFF00B8E6;
    private static final int CYAN_CLEAR = 0x0000B8E6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final long startUptime = SystemClock.uptimeMillis();

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
        float t = (SystemClock.uptimeMillis() - startUptime) / 1000f;
        float span = 0.35f + 0.3f * (0.5f + 0.5f * (float) Math.sin(t * 1.6f));
        float start = ((t * 0.55f) % 1.4f) - 0.4f;
        float from = Math.max(0f, start) * width;
        float to = Math.min(1f, start + span) * width;
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

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) postInvalidateOnAnimation();
    }
}
