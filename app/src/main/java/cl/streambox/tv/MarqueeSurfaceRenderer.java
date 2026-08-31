package cl.streambox.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.Surface;

/** Owns only a title-sized Surface; never reads or mutates an Android View on its worker. */
final class MarqueeSurfaceRenderer implements Choreographer.FrameCallback, AutoCloseable {
    private static final int MAX_CACHED_WIDTH_PX = 2048;
    private static final int MAX_CACHED_HEIGHT_PX = 256;

    interface Listener {
        void onFirstFrame(MarqueeSurfaceRenderer source);
        void onFailure(MarqueeSurfaceRenderer source);
    }

    static final class Spec {
        final String text;
        final TextPaint paint;
        final int width;
        final int height;
        final int titleWidth;
        final int baseline;
        final boolean includeFontPadding;
        final int cycleWidth;
        final float speedPxPerSecond;

        Spec(String text, TextPaint paint, int width, int height, int titleWidth, int baseline,
                boolean includeFontPadding, int gapPx, float speedPxPerSecond) {
            this.text = text;
            this.paint = paint;
            this.width = width;
            this.height = height;
            this.titleWidth = titleWidth;
            this.baseline = baseline;
            this.includeFontPadding = includeFontPadding;
            this.cycleWidth = titleWidth + gapPx;
            this.speedPxPerSecond = speedPxPerSecond;
        }
    }

    private final Object surfaceLock = new Object();
    private final Surface surface; // Borrowed from SurfaceView: do not release it ourselves.
    private final Spec spec;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("VibeM3U-Marquee");
    private final Handler handler;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private volatile boolean closed;

    // All the following state belongs exclusively to the drawing thread.
    private Choreographer choreographer;
    private StaticLayout layout;
    private Bitmap cachedTitle;
    private float textTop;
    private long startFrameTimeNs = -1L;
    private boolean firstFrameSubmitted;

    MarqueeSurfaceRenderer(Surface surface, Spec spec, Listener listener) {
        this.surface = surface;
        this.spec = spec;
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::prepare);
    }

    private void prepare() {
        if (closed) return;
        try {
            layout = StaticLayout.Builder.obtain(spec.text, 0, spec.text.length(), spec.paint,
                            spec.titleWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setIncludePad(spec.includeFontPadding)
                    .setMaxLines(1)
                    .build();
            textTop = spec.baseline - layout.getLineBaseline(0);
            // Most titles fit this small cache. Unusually long titles use the prepared layout
            // directly on this worker instead of allocating an oversized GPU texture.
            if (spec.titleWidth <= MAX_CACHED_WIDTH_PX && spec.height <= MAX_CACHED_HEIGHT_PX) {
                cachedTitle = Bitmap.createBitmap(spec.titleWidth, spec.height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(cachedTitle);
                canvas.translate(0f, textTop);
                layout.draw(canvas);
            }
            if (closed) return;
            // This is the drawing thread's Choreographer, not the Activity's. At most one frame
            // is pending, synchronized to display VSYNC; there is no timer/sleep/busy loop.
            choreographer = Choreographer.getInstance();
            choreographer.postFrameCallback(this);
        } catch (RuntimeException failure) {
            fail();
        }
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (closed) return;
        boolean submitted = false;
        try {
            synchronized (surfaceLock) {
                if (closed || !surface.isValid()) return;
                if (startFrameTimeNs < 0L) startFrameTimeNs = frameTimeNanos;
                float offset = MarqueeMotion.offset(frameTimeNanos - startFrameTimeNs,
                        spec.cycleWidth, spec.speedPxPerSecond);
                Canvas canvas = surface.lockHardwareCanvas(); // Available since minSdk 23.
                try {
                    // Hardware buffers are not preserved between frames. Clear the complete
                    // small surface, not the Activity/video, including after a surface resize.
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    canvas.clipRect(0, 0, spec.width, spec.height);
                    drawCopy(canvas, -offset);
                    drawCopy(canvas, spec.cycleWidth - offset);
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
                submitted = true;
            }
        } catch (RuntimeException failure) {
            fail();
            return;
        }
        if (closed) return;
        if (submitted && !firstFrameSubmitted) {
            firstFrameSubmitted = true;
            listener.onFirstFrame(this); // One notification, not one per frame.
        }
        choreographer.postFrameCallback(this);
    }

    private void drawCopy(Canvas canvas, float left) {
        if (cachedTitle != null) {
            canvas.drawBitmap(cachedTitle, left, 0f, bitmapPaint);
        } else {
            int saveCount = canvas.save();
            canvas.translate(left, textTop);
            layout.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }

    private void fail() {
        if (closed) return;
        listener.onFailure(this);
        close();
    }

    @Override public void close() {
        synchronized (surfaceLock) {
            if (closed) return;
            // Excludes any in-flight lock/draw/post before surfaceDestroyed returns. There is
            // no waiting for a worker callback that could itself need the main thread.
            closed = true;
        }
        handler.post(() -> {
            if (choreographer != null) choreographer.removeFrameCallback(this);
            cachedTitle = null;
            layout = null;
            // Let render buffers release their bitmap references naturally, not recycle()
            // while a submitted hardware frame may still be using the texture.
        });
        thread.quitSafely();
    }
}
