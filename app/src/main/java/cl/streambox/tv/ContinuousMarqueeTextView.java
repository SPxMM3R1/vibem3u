package cl.streambox.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A clipped viewport for two cached copies of a single-line programme title.
 *
 * <p>Only the strip's translation changes during a cycle. Text measurement and
 * layout happen when content or bounds change, never in an animation callback.
 * Each copy has its own small hardware layer; the video and the rest of the
 * overlay are not captured in that layer.</p>
 */
public final class ContinuousMarqueeTextView extends ViewGroup {
    private static final float GAP_DP = 12f;
    private static final float SPEED_DP_PER_SECOND = 40f;
    // Avoid oversized textures on older TVs. Wider titles can still use the
    // normal hardware display list, without a forced off-screen texture.
    private static final int MAX_LAYER_DIMENSION_PX = 2048;

    private final LinearLayout textStrip;
    private final TextView firstCopy;
    private final TextView secondCopy;
    private final int gapPx;
    private final float speedPxPerSecond;
    private final Runnable updateAnimation = this::updateAnimationState;
    private final LinearInterpolator linearInterpolator = new LinearInterpolator();

    private String text = "";
    private ObjectAnimator scrollAnimator;
    private int animatedCycleWidth;

    public ContinuousMarqueeTextView(Context context) {
        this(context, null);
    }

    public ContinuousMarqueeTextView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public ContinuousMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        gapPx = Math.round(GAP_DP * density);
        speedPxPerSecond = SPEED_DP_PER_SECOND * density;
        setClipChildren(true);
        setClipToPadding(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        textStrip = new LinearLayout(context);
        textStrip.setOrientation(LinearLayout.HORIZONTAL);
        textStrip.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        textStrip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        firstCopy = createCopy(context, attrs, defStyleAttr);
        secondCopy = createCopy(context, attrs, defStyleAttr);
        secondCopy.setVisibility(INVISIBLE);
        textStrip.addView(firstCopy, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams repeatedParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        repeatedParams.leftMargin = gapPx;
        textStrip.addView(secondCopy, repeatedParams);
        addView(textStrip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        setText(firstCopy.getText());
    }

    private TextView createCopy(Context context, AttributeSet attrs, int defStyleAttr) {
        TextView copy = new TextView(context, attrs, defStyleAttr);
        // The XML id and outer padding belong to the viewport, not both copies.
        copy.setId(NO_ID);
        copy.setPadding(0, 0, 0, 0);
        copy.setSingleLine(true);
        copy.setEllipsize(null);
        copy.setFocusable(false);
        copy.setClickable(false);
        return copy;
    }

    public void setText(CharSequence value) {
        String normalized = value == null ? ""
                : value.toString().replace('\n', ' ').replace('\r', ' ');
        if (TextUtils.equals(text, normalized)) return;
        stopAnimation();
        text = normalized;
        firstCopy.setText(text);
        secondCopy.setText(text);
        setContentDescription(text);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int horizontalPadding = getPaddingLeft() + getPaddingRight();
        int verticalPadding = getPaddingTop() + getPaddingBottom();
        int stripHeightSpec = getChildMeasureSpec(heightMeasureSpec, verticalPadding,
                LayoutParams.WRAP_CONTENT);
        // Give each native TextView its full width. The viewport, rather than
        // a truncated TextView, clips the moving title to the programme area.
        textStrip.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), stripHeightSpec);
        int desiredWidth = Math.max(getSuggestedMinimumWidth(),
                firstCopy.getMeasuredWidth() + horizontalPadding);
        int desiredHeight = Math.max(getSuggestedMinimumHeight(),
                textStrip.getMeasuredHeight() + verticalPadding);
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int stripLeft = getPaddingLeft();
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
            stripLeft = getWidth() - getPaddingRight() - firstCopy.getMeasuredWidth();
        }
        textStrip.layout(stripLeft, getPaddingTop(),
                stripLeft + textStrip.getMeasuredWidth(),
                getPaddingTop() + textStrip.getMeasuredHeight());
        scheduleAnimationUpdate();
    }

    private void scheduleAnimationUpdate() {
        if (textStrip == null) return; // View callbacks can run during construction.
        removeCallbacks(updateAnimation);
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE
                || !hasWindowFocus()) {
            stopAnimation();
        } else {
            // Run after layout. This is a state-change callback, not a frame loop.
            post(updateAnimation);
        }
    }

    private void updateAnimationState() {
        // A visibility/focus callback can arrive before a requested relayout.
        // onLayout will schedule us again with the new text dimensions.
        if (isLayoutRequested() || textStrip.isLayoutRequested()) return;
        int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int titleWidth = firstCopy.getWidth();
        boolean animationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ValueAnimator.areAnimatorsEnabled();
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE
                || !hasWindowFocus() || !isHardwareAccelerated() || !animationsEnabled
                || getLayoutDirection() == LAYOUT_DIRECTION_RTL || text.isEmpty()
                || availableWidth <= 0 || titleWidth <= availableWidth || firstCopy.getHeight() <= 0) {
            stopAnimation();
            return;
        }

        int cycleWidth = titleWidth + gapPx;
        if (scrollAnimator != null && scrollAnimator.isStarted()
                && animatedCycleWidth == cycleWidth) return;
        stopAnimation();
        animatedCycleWidth = cycleWidth;
        secondCopy.setVisibility(VISIBLE);
        prepareLayer(firstCopy);
        prepareLayer(secondCopy);

        // One property animator keeps both copies exactly in phase. The repeat
        // starts with the same image as the end, so no blank interval is needed.
        scrollAnimator = ObjectAnimator.ofFloat(textStrip, View.TRANSLATION_X, 0f, -cycleWidth);
        scrollAnimator.setDuration(Math.max(1L, Math.round(cycleWidth * 1000d / speedPxPerSecond)));
        scrollAnimator.setInterpolator(linearInterpolator);
        scrollAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scrollAnimator.setRepeatMode(ValueAnimator.RESTART);
        scrollAnimator.start();
    }

    private void prepareLayer(TextView copy) {
        if (copy.getWidth() <= MAX_LAYER_DIMENSION_PX && copy.getHeight() <= MAX_LAYER_DIMENSION_PX) {
            copy.setLayerType(LAYER_TYPE_HARDWARE, null);
            copy.buildLayer();
        }
    }

    private void stopAnimation() {
        removeCallbacks(updateAnimation);
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        animatedCycleWidth = 0;
        if (textStrip == null) return;
        textStrip.setTranslationX(0f);
        firstCopy.setLayerType(LAYER_TYPE_NONE, null);
        secondCopy.setLayerType(LAYER_TYPE_NONE, null);
        secondCopy.setVisibility(INVISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleAnimationUpdate();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        scheduleAnimationUpdate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        scheduleAnimationUpdate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        scheduleAnimationUpdate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }
}
