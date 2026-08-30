package cl.streambox.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/**
 * A single-line text view that continuously wraps an overflowing title.
 *
 * <p>Android's built-in marquee can leave a long blank interval between
 * repetitions. This view draws two adjacent copies instead, so the next copy
 * starts entering as the current one leaves the available area.</p>
 */
public final class ContinuousMarqueeTextView extends TextView {
    private static final float GAP_DP = 12f;
    private static final float SPEED_DP_PER_SECOND = 40f;
    // El texto no necesita sincronizarse con cada frame del video. Limitarlo a
    // 30 Hz evita que un canal a 60/120 fps comparta cada invalidacion de la
    // superficie con la animacion del titulo.
    private static final long MARQUEE_FRAME_INTERVAL_MS = 33L;

    private final float gapPx;
    private final float speedPxPerSecond;

    private String renderedText = "";
    private float textWidth;
    private float cycleWidth;
    private float scrollOffset;
    private long lastFrameNanos;
    private boolean overflowing;

    public ContinuousMarqueeTextView(Context context) {
        super(context);
        gapPx = dp(GAP_DP);
        speedPxPerSecond = dp(SPEED_DP_PER_SECOND);
        configure();
    }

    public ContinuousMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gapPx = dp(GAP_DP);
        speedPxPerSecond = dp(SPEED_DP_PER_SECOND);
        configure();
    }

    public ContinuousMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gapPx = dp(GAP_DP);
        speedPxPerSecond = dp(SPEED_DP_PER_SECOND);
        configure();
    }

    private void configure() {
        setMaxLines(1);
        setEllipsize(null);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        String text = getDisplayText();
        if (TextUtils.isEmpty(text)
                || getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                || !updateMetrics(text)) {
            stopAnimation();
            super.onDraw(canvas);
            return;
        }

        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
        } else {
            float elapsedSeconds = (now - lastFrameNanos) / 1_000_000_000f;
            if (elapsedSeconds > 0f && elapsedSeconds < 0.5f) {
                scrollOffset = (scrollOffset + elapsedSeconds * speedPxPerSecond) % cycleWidth;
            }
            lastFrameNanos = now;
        }

        int contentLeft = getCompoundPaddingLeft();
        int contentRight = getWidth() - getCompoundPaddingRight();
        if (contentRight <= contentLeft || cycleWidth <= 0f) {
            stopAnimation();
            super.onDraw(canvas);
            return;
        }

        float baseline = getBaseline();
        if (baseline < 0f) {
            baseline = getPaddingTop() - getPaint().ascent();
        }

        int saveCount = canvas.save();
        canvas.clipRect(contentLeft, 0, contentRight, getHeight());
        float firstCopyX = contentLeft - scrollOffset;
        Paint paint = getPaint();
        canvas.drawText(text, firstCopyX, baseline, paint);
        canvas.drawText(text, firstCopyX + cycleWidth, baseline, paint);
        canvas.restoreToCount(saveCount);

        // No usar la frecuencia del display para el marquee: la reproduccion
        // conserva su cadencia nativa y el texto se actualiza a una frecuencia
        // suficiente para verse fluido, con menos trabajo en el hilo de UI.
        postInvalidateDelayed(MARQUEE_FRAME_INTERVAL_MS);
    }

    private String getDisplayText() {
        return normalizeText(getText());
    }

    private String normalizeText(CharSequence text) {
        if (text == null) return "";
        return text.toString().replace('\n', ' ').replace('\r', ' ');
    }

    private boolean updateMetrics(String text) {
        float availableWidth = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        float measuredWidth = getPaint().measureText(text);
        boolean newOverflowing = availableWidth > 0f && measuredWidth > availableWidth;
        boolean changed = !text.equals(renderedText)
                || Math.abs(measuredWidth - textWidth) > 0.5f
                || newOverflowing != overflowing;

        renderedText = text;
        textWidth = measuredWidth;
        cycleWidth = textWidth + gapPx;
        overflowing = newOverflowing;
        if (changed) resetAnimation();
        return overflowing;
    }

    private void stopAnimation() {
        overflowing = false;
        scrollOffset = 0f;
        lastFrameNanos = 0L;
    }

    private void resetAnimation() {
        scrollOffset = 0f;
        lastFrameNanos = 0L;
        invalidate();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        if (!normalizeText(text).equals(renderedText)) resetAnimation();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }
}
