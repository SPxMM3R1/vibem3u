package cl.streambox.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A native, static title plus a small independent surface used only while scrolling.
 *
 * <p>All View access stays on the UI thread. The renderer receives a copy of the text/style and
 * owns its frame loop, text cache and Canvas on a separate Looper. No per-frame invalidate,
 * translation, layout or callback is posted to the Activity's UI thread.</p>
 */
public final class ContinuousMarqueeTextView extends FrameLayout implements SurfaceHolder.Callback {
    private static final float GAP_DP = 12f;
    private static final float SPEED_DP_PER_SECOND = 40f;
    private static final int MAX_TITLE_WIDTH_PX = 32_768;

    private final TextView staticTitle;
    private final SurfaceView scrollingSurface;
    private final int gapPx;
    private final float speedPxPerSecond;
    private final Runnable updateAnimation = this::updateAnimationState;

    private String text = "";
    private MarqueeSurfaceRenderer.Spec activeSpec;
    private MarqueeSurfaceRenderer renderer;
    private boolean surfaceFailed;
    private boolean released;

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

        staticTitle = new TextView(context, attrs, defStyleAttr);
        staticTitle.setId(NO_ID);
        staticTitle.setPadding(0, 0, 0, 0);
        staticTitle.setSingleLine(true);
        staticTitle.setEllipsize(TextUtils.TruncateAt.END);
        staticTitle.setFocusable(false);
        staticTitle.setClickable(false);
        staticTitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(staticTitle, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        scrollingSurface = new SurfaceView(context);
        // Only this title-sized transparent surface is above the Activity's window. The video
        // surface and the rest of the OSD are neither captured nor moved into a bitmap/texture.
        scrollingSurface.setZOrderOnTop(true);
        scrollingSurface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        scrollingSurface.getHolder().addCallback(this);
        scrollingSurface.setFocusable(false);
        scrollingSurface.setClickable(false);
        scrollingSurface.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrollingSurface.setVisibility(INVISIBLE);
        addView(scrollingSurface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setText(staticTitle.getText());
    }

    public void setText(CharSequence value) {
        String normalized = value == null ? ""
                : value.toString().replace('\n', ' ').replace('\r', ' ');
        if (TextUtils.equals(text, normalized)) return;
        stopScroll();
        surfaceFailed = false;
        text = normalized;
        staticTitle.setText(text);
        setContentDescription(text);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int horizontalPadding = getPaddingLeft() + getPaddingRight();
        int verticalPadding = getPaddingTop() + getPaddingBottom();
        staticTitle.measure(getChildMeasureSpec(widthMeasureSpec, horizontalPadding, LayoutParams.MATCH_PARENT),
                getChildMeasureSpec(heightMeasureSpec, verticalPadding, LayoutParams.WRAP_CONTENT));
        // A MATCH_PARENT SurfaceView must not enlarge this WRAP_CONTENT title to the OSD's height.
        setMeasuredDimension(resolveSize(staticTitle.getMeasuredWidth() + horizontalPadding, widthMeasureSpec),
                resolveSize(staticTitle.getMeasuredHeight() + verticalPadding, heightMeasureSpec));
        int childWidth = MeasureSpec.makeMeasureSpec(
                Math.max(0, getMeasuredWidth() - horizontalPadding), MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(
                Math.max(0, getMeasuredHeight() - verticalPadding), MeasureSpec.EXACTLY);
        staticTitle.measure(childWidth, childHeight);
        scrollingSurface.measure(childWidth, childHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        scheduleAnimationUpdate();
    }

    private boolean canScroll() {
        return !released && !surfaceFailed && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && hasWindowFocus() && isHardwareAccelerated()
                && getLayoutDirection() != LAYOUT_DIRECTION_RTL && !text.isEmpty()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled());
    }

    private void scheduleAnimationUpdate() {
        if (staticTitle == null) return; // View callbacks can occur during construction.
        removeCallbacks(updateAnimation);
        if (!canScroll()) {
            stopScroll();
        } else {
            post(updateAnimation); // A layout/state change, never a per-frame callback.
        }
    }

    private void updateAnimationState() {
        if (isLayoutRequested() || staticTitle.isLayoutRequested()) return;
        int width = staticTitle.getWidth();
        int height = staticTitle.getHeight();
        if (!canScroll() || width <= 0 || height <= 0) {
            stopScroll();
            return;
        }
        // Diagnostic TextView updates may relayout the OSD, but must not restart or remeasure
        // the animation when the title's content and geometry are unchanged.
        if (activeSpec != null && activeSpec.width == width && activeSpec.height == height) return;
        float desiredWidth = Layout.getDesiredWidth(text, staticTitle.getPaint());
        if (!Float.isFinite(desiredWidth) || desiredWidth <= width || desiredWidth > MAX_TITLE_WIDTH_PX) {
            stopScroll();
            return;
        }

        stopScroll();
        TextPaint paint = new TextPaint(staticTitle.getPaint());
        paint.setColor(staticTitle.getCurrentTextColor());
        activeSpec = new MarqueeSurfaceRenderer.Spec(text, paint, width, height,
                (int) Math.ceil(desiredWidth), staticTitle.getBaseline(),
                staticTitle.getIncludeFontPadding(), gapPx, speedPxPerSecond);
        scrollingSurface.setVisibility(VISIBLE);
        // The static TextView remains visible until the surface has actually submitted a frame.
        if (scrollingSurface.getHolder().getSurface().isValid()) startRenderer();
    }

    private void startRenderer() {
        if (activeSpec == null || !canScroll() || renderer != null) return;
        renderer = new MarqueeSurfaceRenderer(scrollingSurface.getHolder().getSurface(), activeSpec,
                new MarqueeSurfaceRenderer.Listener() {
                    @Override public void onFirstFrame(MarqueeSurfaceRenderer source) {
                        post(() -> {
                            if (renderer == source && activeSpec != null && canScroll()) {
                                staticTitle.setVisibility(INVISIBLE);
                            }
                        });
                    }

                    @Override public void onFailure(MarqueeSurfaceRenderer source) {
                        post(() -> {
                            if (renderer != source) return;
                            surfaceFailed = true;
                            stopScroll(); // Safe native text instead of a black/invalid surface.
                        });
                    }
                });
    }

    private void stopRenderer() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (staticTitle != null) staticTitle.setVisibility(VISIBLE);
    }

    private void stopScroll() {
        removeCallbacks(updateAnimation);
        activeSpec = null;
        stopRenderer();
        if (scrollingSurface != null) scrollingSurface.setVisibility(INVISIBLE);
    }

    /** Stops the worker when the Activity explicitly releases its resources. */
    void release() {
        released = true;
        stopScroll();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        startRenderer();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        scheduleAnimationUpdate();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // close() excludes any in-flight Canvas operation before this callback returns.
        stopRenderer();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleAnimationUpdate();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        scheduleAnimationUpdate();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        scheduleAnimationUpdate();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) surfaceFailed = false;
        scheduleAnimationUpdate();
    }

    @Override protected void onDetachedFromWindow() {
        stopScroll();
        super.onDetachedFromWindow();
    }
}
