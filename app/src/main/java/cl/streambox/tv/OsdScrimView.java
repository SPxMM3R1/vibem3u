package cl.streambox.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Fondo del OSD con el lenguaje de la Guía: oscuro abajo a la izquierda (donde va el
 * texto) y abriéndose hacia la derecha y hacia arriba para dejar ver el video, más una
 * sombra suave en la esquina superior derecha para la fecha y la hora.
 */
public final class OsdScrimView extends View {
    private static final int BASE = 0x05080A;

    private final Paint leftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        return (Math.round(alpha * 255f) << 24) | BASE;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        float leftTop = height - dp(280);
        // De izquierda a derecha, enmascarado para que se desvanezca hacia arriba.
        Shader horizontal = new LinearGradient(0f, 0f, width, 0f,
                new int[] {base(.92f), base(.80f), base(.35f), base(.10f)},
                new float[] {0f, .34f, .64f, 1f}, Shader.TileMode.CLAMP);
        Shader fadeUp = new LinearGradient(0f, leftTop, 0f, height,
                new int[] {0x00000000, 0xFF000000, 0xFF000000},
                new float[] {0f, .45f, 1f}, Shader.TileMode.CLAMP);
        leftPaint.setShader(new ComposeShader(horizontal, fadeUp, PorterDuff.Mode.DST_IN));
        bottomPaint.setShader(new LinearGradient(0f, height - dp(110), 0f, height,
                base(0f), base(.85f), Shader.TileMode.CLAMP));
        cornerPaint.setShader(new RadialGradient(width, 0f, dp(260),
                new int[] {base(.75f), base(0f)}, new float[] {0f, .75f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.drawRect(0f, height - dp(280), width, height, leftPaint);
        canvas.drawRect(0f, height - dp(110), width, height, bottomPaint);
        // Elipse aplanada en la esquina: 260 dp de ancho por 85 dp de alto.
        canvas.save();
        canvas.scale(1f, 85f / 260f, width, 0f);
        canvas.drawRect(width - dp(260), 0f, width, dp(260), cornerPaint);
        canvas.restore();
    }
}
