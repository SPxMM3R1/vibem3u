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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates the bitrate of recently loaded media content, independently for
 * video and audio tracks or as one aggregate for a multiplexed stream.
 *
 * <p>This deliberately does not use the wall-clock duration of the HTTP
 * transfer. A segment can be downloaded faster than it is played because it
 * fills the playback buffer. Instead, the number of loaded bytes is divided
 * by the media-time interval represented by the segment, which is the same
 * distinction that media players make between content bitrate and network
 * throughput.</p>
 */
@UnstableApi
final class PlaybackBitrateMeter implements AnalyticsListener, VideoFrameMetadataListener {
    private static final long WINDOW_MEDIA_DURATION_MS = 10_000L;
    private static final long MAX_SAMPLE_DURATION_MS = 120_000L;
    private static final long FPS_WINDOW_REALTIME_MS = 1_000L;
    private static final long FPS_WINDOW_REALTIME_NS = 1_000_000_000L;
    private static final float LOWEST_NORMALIZED_FRAME_RATE = 20f;
    private static final float FIRST_FRAME_RATE_GROUP_END = 27f;
    private static final float SECOND_FRAME_RATE_GROUP_END = 40f;
    private static final float THIRD_FRAME_RATE_GROUP_END = 55f;
    private static final float HIGHEST_NORMALIZED_FRAME_RATE = 66f;

    private enum FrameRateSource {
        NONE,
        PROCESSED,
        RENDERED
    }

    interface Listener {
        void onMeasuredBitrateChanged();
    }

    private final Listener listener;
    private final Deque<Sample> videoSamples = new ArrayDeque<>();
    private final Deque<Sample> audioSamples = new ArrayDeque<>();
    private final Deque<Sample> streamSamples = new ArrayDeque<>();
    private boolean muxedStream;
    private float measuredFrameRate;
    private long fpsWindowStartRealtimeMs = C.TIME_UNSET;
    private long fpsWindowFrameCount;
    private FrameRateSource frameRateSource = FrameRateSource.NONE;
    private long renderedFpsWindowStartRealtimeNs = C.TIME_UNSET;
    private long renderedFpsWindowFrameCount;
    private boolean renderedVideoFrameObserved;

    PlaybackBitrateMeter(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onLoadCompleted(
            AnalyticsListener.EventTime eventTime,
            LoadEventInfo loadEventInfo,
            MediaLoadData mediaLoadData
    ) {
        if (loadEventInfo == null || mediaLoadData == null
                || mediaLoadData.dataType != C.DATA_TYPE_MEDIA) {
            return;
        }

        long mediaStartMs = mediaLoadData.mediaStartTimeMs;
        long mediaEndMs = mediaLoadData.mediaEndTimeMs;
        if (mediaStartMs == C.TIME_UNSET || mediaEndMs == C.TIME_UNSET) return;

        recordMediaSample(
                mediaLoadData.trackType,
                loadEventInfo.bytesLoaded,
                mediaStartMs,
                mediaEndMs
        );
    }

    @Override
    public void onVideoInputFormatChanged(
            AnalyticsListener.EventTime eventTime,
            Format format,
            DecoderReuseEvaluation decoderReuseEvaluation
    ) {
        synchronized (this) {
            resetFpsWindow();
        }
        if (listener != null) listener.onMeasuredBitrateChanged();
    }

    @Override
    public void onVideoFrameProcessingOffset(
            AnalyticsListener.EventTime eventTime,
            long totalProcessingOffsetUs,
            int frameCount
    ) {
        if (eventTime == null) return;
        synchronized (this) {
            if (frameRateSource == FrameRateSource.RENDERED) return;
            if (frameRateSource == FrameRateSource.NONE) {
                frameRateSource = FrameRateSource.PROCESSED;
            }
        }
        recordFrameSample(eventTime.realtimeMs, frameCount);
    }

    /**
     * Receives a callback from the active video renderer immediately before a frame is submitted to
     * the video output. This is a closer approximation of the playback frame rate than the
     * aggregated processing-offset analytics event, and works even when the stream has no frame
     * rate in its manifest.
     */
    @Override
    public void onVideoFrameAboutToBeRendered(
            long presentationTimeUs,
            long releaseTimeNs,
            Format format,
            @Nullable MediaFormat mediaFormat
    ) {
        long realtimeNs = System.nanoTime();
        synchronized (this) {
            if (frameRateSource != FrameRateSource.RENDERED) {
                resetFpsWindow();
                frameRateSource = FrameRateSource.RENDERED;
            }
        }
        recordRenderedFrame(realtimeNs);
    }

    /**
     * Estimates the rate of video frames processed by Media3. The first
     * callback establishes a time origin because its batch may have been
     * accumulated before it was dispatched. The UI uses this measurement as
     * its only FPS source, so it does not depend on optional manifest metadata.
     */
    synchronized void recordFrameSample(long realtimeMs, int frameCount) {
        if (realtimeMs == C.TIME_UNSET || frameCount <= 0) return;

        if (fpsWindowStartRealtimeMs == C.TIME_UNSET
                || realtimeMs < fpsWindowStartRealtimeMs) {
            fpsWindowStartRealtimeMs = realtimeMs;
            fpsWindowFrameCount = 0L;
            measuredFrameRate = 0f;
            return;
        }

        fpsWindowFrameCount += frameCount;
        long elapsedMs = realtimeMs - fpsWindowStartRealtimeMs;
        if (elapsedMs < FPS_WINDOW_REALTIME_MS) return;

        measuredFrameRate = fpsWindowFrameCount * 1_000f / elapsedMs;
        fpsWindowStartRealtimeMs = realtimeMs;
        fpsWindowFrameCount = 0L;
        if (listener != null) listener.onMeasuredBitrateChanged();
    }

    /**
     * Records one frame submitted by the video renderer. The calculation uses the interval between
     * the first and last frame in the window, so a sequence of 31 frames over one second reports
     * 30 FPS rather than 31 FPS.
     */
    synchronized void recordRenderedFrame(long realtimeNs) {
        if (realtimeNs < 0L) return;

        boolean firstRenderedFrame = !renderedVideoFrameObserved;
        renderedVideoFrameObserved = true;
        if (firstRenderedFrame && listener != null) {
            // Let the UI expose the track metadata as soon as playback has
            // crossed the first-frame boundary, without waiting for the FPS
            // measurement window to complete.
            listener.onMeasuredBitrateChanged();
        }

        if (renderedFpsWindowStartRealtimeNs == C.TIME_UNSET
                || realtimeNs < renderedFpsWindowStartRealtimeNs
                || realtimeNs - renderedFpsWindowStartRealtimeNs > 4L * FPS_WINDOW_REALTIME_NS) {
            renderedFpsWindowStartRealtimeNs = realtimeNs;
            renderedFpsWindowFrameCount = 1L;
            measuredFrameRate = 0f;
            return;
        }

        renderedFpsWindowFrameCount++;
        long elapsedNs = realtimeNs - renderedFpsWindowStartRealtimeNs;
        if (elapsedNs < FPS_WINDOW_REALTIME_NS || renderedFpsWindowFrameCount < 2L) return;

        measuredFrameRate = (renderedFpsWindowFrameCount - 1L)
                * 1_000_000_000f / elapsedNs;
        renderedFpsWindowStartRealtimeNs = realtimeNs;
        renderedFpsWindowFrameCount = 1L;
        if (listener != null) listener.onMeasuredBitrateChanged();
    }

    synchronized float getMeasuredFrameRate() {
        return measuredFrameRate;
    }

    synchronized boolean hasRenderedVideoFrame() {
        return renderedVideoFrameObserved;
    }

    /**
     * Groups small measurement variations into the standard frame-rate families used by video
     * streams. Values outside the known families remain untouched instead of being presented as a
     * different frame rate.
     */
    static float normalizeFrameRate(float frameRate) {
        if (!Float.isFinite(frameRate) || frameRate <= 0f) return frameRate;
        if (frameRate >= LOWEST_NORMALIZED_FRAME_RATE
                && frameRate < FIRST_FRAME_RATE_GROUP_END) {
            return 24f;
        }
        if (frameRate >= FIRST_FRAME_RATE_GROUP_END
                && frameRate < SECOND_FRAME_RATE_GROUP_END) {
            return 30f;
        }
        if (frameRate >= SECOND_FRAME_RATE_GROUP_END
                && frameRate < THIRD_FRAME_RATE_GROUP_END) {
            return 50f;
        }
        if (frameRate >= THIRD_FRAME_RATE_GROUP_END
                && frameRate <= HIGHEST_NORMALIZED_FRAME_RATE) {
            return 60f;
        }
        return frameRate;
    }

    /**
     * Records one completed media segment. Package visibility keeps the
     * calculation deterministic and testable without constructing Media3
     * event objects or storing any stream data.
     */
    synchronized void recordMediaSample(
            int trackType,
            long bytesLoaded,
            long mediaStartMs,
            long mediaEndMs
    ) {
        if (bytesLoaded <= 0 || mediaEndMs <= mediaStartMs) {
            return;
        }
        long durationMs = mediaEndMs - mediaStartMs;
        if (durationMs <= 0 || durationMs > MAX_SAMPLE_DURATION_MS) return;

        Deque<Sample> samples;
        if (trackType == C.TRACK_TYPE_VIDEO) {
            samples = videoSamples;
        } else if (trackType == C.TRACK_TYPE_AUDIO) {
            samples = audioSamples;
        } else if (trackType == C.TRACK_TYPE_DEFAULT && muxedStream) {
            // A multiplexed HLS chunk contains audio and video in the same
            // payload. Keep its aggregate bitrate separate from the per-track
            // figures because this event cannot divide the bytes reliably.
            samples = streamSamples;
        } else {
            // Text, metadata and other unsupported track types do not belong
            // in the audio/video/aggregate content bitrate.
            return;
        }

        samples.addLast(new Sample(bytesLoaded, durationMs));
        trimWindow(samples);
        if (listener != null) listener.onMeasuredBitrateChanged();
    }

    synchronized long getVideoBitrate() {
        return calculateBitrate(videoSamples);
    }

    synchronized long getAudioBitrate() {
        return calculateBitrate(audioSamples);
    }

    synchronized long getStreamBitrate() {
        return calculateBitrate(streamSamples);
    }

    synchronized boolean isMuxedStream() {
        return muxedStream;
    }

    synchronized void setMuxedStream(boolean muxedStream) {
        this.muxedStream = muxedStream;
    }

    synchronized void reset() {
        videoSamples.clear();
        audioSamples.clear();
        streamSamples.clear();
        muxedStream = false;
        resetFpsWindow();
        frameRateSource = FrameRateSource.NONE;
        renderedVideoFrameObserved = false;
    }

    private void resetFpsWindow() {
        measuredFrameRate = 0f;
        fpsWindowStartRealtimeMs = C.TIME_UNSET;
        fpsWindowFrameCount = 0L;
        renderedFpsWindowStartRealtimeNs = C.TIME_UNSET;
        renderedFpsWindowFrameCount = 0L;
    }

    private static void trimWindow(Deque<Sample> samples) {
        long durationMs = totalDuration(samples);
        while (samples.size() > 1 && durationMs > WINDOW_MEDIA_DURATION_MS) {
            Sample removed = samples.removeFirst();
            durationMs -= removed.durationMs;
        }
    }

    private static long calculateBitrate(Deque<Sample> samples) {
        if (samples.isEmpty()) return 0L;
        long totalBytes = 0L;
        long totalDurationMs = 0L;
        for (Sample sample : samples) {
            totalBytes += sample.bytes;
            totalDurationMs += sample.durationMs;
        }
        if (totalBytes <= 0 || totalDurationMs <= 0) return 0L;
        return Math.round(totalBytes * 8_000d / totalDurationMs);
    }

    private static long totalDuration(Deque<Sample> samples) {
        long total = 0L;
        for (Sample sample : samples) total += sample.durationMs;
        return total;
    }

    private static final class Sample {
        final long bytes;
        final long durationMs;

        Sample(long bytes, long durationMs) {
            this.bytes = bytes;
            this.durationMs = durationMs;
        }
    }
}
