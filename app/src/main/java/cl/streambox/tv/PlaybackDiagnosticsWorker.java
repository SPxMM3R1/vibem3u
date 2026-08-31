package cl.streambox.tv;

import android.media.MediaFormat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated calculation thread for video FPS and media-content bitrates.
 *
 * <p>Media3 callbacks only hand off primitive input. Immutable snapshots are readable without
 * blocking the UI or video renderer. Reset swaps the entire measurement session synchronously,
 * so queued work from an old channel can never publish into the current session.</p>
 */
@UnstableApi
final class PlaybackDiagnosticsWorker implements AnalyticsListener, VideoFrameMetadataListener, AutoCloseable {
    private static final int FRAME_BUFFER_CAPACITY = 512;
    private static final long SAMPLE_INTERVAL_MS = 250L;
    private static final long NOTIFICATION_INTERVAL_NS = 1_000_000_000L;

    interface Listener {
        void onMeasurementsChanged();
    }

    interface NanoClock {
        long now();
    }

    private interface SessionAction {
        void apply(Session session);
    }

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(0f, 0L, 0L, 0L, false, false);

        final float measuredFrameRate;
        final float displayFrameRate;
        final long videoBitrate;
        final long audioBitrate;
        final long streamBitrate;
        final boolean muxedStream;
        final boolean hasRenderedVideoFrame;

        Snapshot(float measuredFrameRate, long videoBitrate, long audioBitrate, long streamBitrate,
                boolean muxedStream, boolean hasRenderedVideoFrame) {
            this.measuredFrameRate = measuredFrameRate;
            this.displayFrameRate = PlaybackBitrateMeter.normalizeFrameRate(measuredFrameRate);
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
            this.streamBitrate = streamBitrate;
            this.muxedStream = muxedStream;
            this.hasRenderedVideoFrame = hasRenderedVideoFrame;
        }

        boolean sameValues(Snapshot other) {
            return Float.compare(measuredFrameRate, other.measuredFrameRate) == 0
                    && videoBitrate == other.videoBitrate && audioBitrate == other.audioBitrate
                    && streamBitrate == other.streamBitrate && muxedStream == other.muxedStream
                    && hasRenderedVideoFrame == other.hasRenderedVideoFrame;
        }
    }

    private static final class Session {
        final PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);
        final FrameTimestampBuffer frames = new FrameTimestampBuffer(FRAME_BUFFER_CAPACITY);
        volatile Snapshot snapshot = Snapshot.EMPTY;
        // Only the diagnostics worker accesses notification state.
        Snapshot lastNotified = Snapshot.EMPTY;
        long lastNotificationNs = Long.MIN_VALUE;
    }

    private final Listener listener;
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> sampling;
    private final NanoClock nanoTime;
    private final long[] frameBatch = new long[FRAME_BUFFER_CAPACITY];
    private volatile Session session = new Session();
    private volatile boolean closed;
    private volatile boolean notificationsEnabled;

    PlaybackDiagnosticsWorker(Listener listener) {
        this(listener, newExecutor());
    }

    PlaybackDiagnosticsWorker(Listener listener, ScheduledExecutorService executor) {
        this(listener, executor, System::nanoTime);
    }

    PlaybackDiagnosticsWorker(Listener listener, ScheduledExecutorService executor, NanoClock nanoTime) {
        this.listener = listener;
        this.executor = executor;
        this.nanoTime = nanoTime;
        sampling = executor.scheduleWithFixedDelay(this::sampleFrames,
                SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService newExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "VibeM3U-Diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    Snapshot snapshot() {
        return session.snapshot;
    }

    void setNotificationsEnabled(boolean enabled) {
        notificationsEnabled = enabled;
    }

    void setMuxedStream(boolean muxedStream) {
        submit(current -> current.meter.setMuxedStream(muxedStream));
    }

    void reset() {
        // The old worker may finish a calculation, but its object is no longer visible to UI.
        // The per-session frame buffer also excludes any batch queued before this reset.
        if (!closed) session = new Session();
    }

    @Override public void onLoadCompleted(EventTime eventTime, LoadEventInfo loadEventInfo,
            MediaLoadData mediaLoadData) {
        if (loadEventInfo == null || mediaLoadData == null
                || mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return;
        long startMs = mediaLoadData.mediaStartTimeMs;
        long endMs = mediaLoadData.mediaEndTimeMs;
        if (startMs == C.TIME_UNSET || endMs == C.TIME_UNSET) return;
        recordMediaSample(mediaLoadData.trackType, loadEventInfo.bytesLoaded, startMs, endMs);
    }

    void recordMediaSample(int trackType, long bytes, long startMs, long endMs) {
        submit(current -> current.meter.recordMediaSample(trackType, bytes, startMs, endMs));
    }

    @Override public void onVideoInputFormatChanged(EventTime eventTime, Format format,
            DecoderReuseEvaluation decoderReuseEvaluation) {
        submit(current -> current.meter.resetFrameRate());
    }

    @Override public void onVideoFrameProcessingOffset(EventTime eventTime,
            long totalProcessingOffsetUs, int frameCount) {
        if (eventTime == null) return;
        long realtimeMs = eventTime.realtimeMs;
        submit(current -> current.meter.recordFrameSample(realtimeMs, frameCount));
    }

    @Override public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs,
            Format format, @Nullable MediaFormat mediaFormat) {
        recordRenderedFrame(System.nanoTime());
    }

    void recordRenderedFrame(long realtimeNs) {
        if (closed) return;
        Session current = session;
        // Capture the renderer timestamp HERE, not when the worker drains it. Queue latency
        // must not become a false drop in measured FPS. No Runnable is allocated per frame.
        current.frames.add(realtimeNs);
    }

    private void submit(SessionAction action) {
        if (closed) return;
        Session expected = session;
        try {
            executor.execute(() -> {
                if (closed || session != expected) return;
                action.apply(expected);
                publish(expected);
            });
        } catch (RejectedExecutionException rejected) {
            if (!closed) throw rejected;
        }
    }

    private void sampleFrames() {
        if (closed) return;
        Session current = session;
        int count = current.frames.drainTo(frameBatch);
        for (int index = 0; index < count; index++) {
            current.meter.recordRenderedFrame(frameBatch[index]);
        }
        publish(current);
    }

    private void publish(Session expected) {
        if (closed || session != expected) return;
        PlaybackBitrateMeter meter = expected.meter;
        Snapshot measured = new Snapshot(meter.getMeasuredFrameRate(), meter.getVideoBitrate(),
                meter.getAudioBitrate(), meter.getStreamBitrate(), meter.isMuxedStream(),
                meter.hasRenderedVideoFrame());
        if (!measured.sameValues(expected.snapshot)) expected.snapshot = measured;
        if (!notificationsEnabled || listener == null || measured.sameValues(expected.lastNotified)) return;
        long nowNs = nanoTime.now();
        boolean firstFrame = measured.hasRenderedVideoFrame && !expected.lastNotified.hasRenderedVideoFrame;
        if (!firstFrame && expected.lastNotificationNs != Long.MIN_VALUE
                && nowNs - expected.lastNotificationNs < NOTIFICATION_INTERVAL_NS) return;
        // Keep lastNotified separate from the latest snapshot: a throttled change must still be
        // delivered on a later tick even if its numeric value no longer changes.
        expected.lastNotified = measured;
        expected.lastNotificationNs = nowNs;
        if (!closed && session == expected) listener.onMeasurementsChanged();
    }

    @Override public void close() {
        closed = true;
        notificationsEnabled = false;
        session = new Session();
        sampling.cancel(false);
        executor.shutdownNow();
    }
}
