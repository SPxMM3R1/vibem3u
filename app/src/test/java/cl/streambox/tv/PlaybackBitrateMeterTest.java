package cl.streambox.tv;

import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackBitrateMeterTest {
    @Test
    public void computesVideoAndAudioBitrateIndependently() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        meter.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_500_000L, 0L, 4_000L);
        meter.recordMediaSample(C.TRACK_TYPE_AUDIO, 80_000L, 0L, 4_000L);

        assertEquals(5_000_000L, meter.getVideoBitrate());
        assertEquals(160_000L, meter.getAudioBitrate());
    }

    @Test
    public void usesMediaDurationAndNotTransferDuration() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        // The segment could have been downloaded in 500 ms, but it represents
        // four seconds of media and must therefore report 4 Mbps.
        meter.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 10_000L, 14_000L);

        assertEquals(4_000_000L, meter.getVideoBitrate());
    }

    @Test
    public void calculatesWeightedRollingAverage() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        meter.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 0L, 4_000L);
        meter.recordMediaSample(C.TRACK_TYPE_VIDEO, 3_000_000L, 4_000L, 8_000L);

        assertEquals(5_000_000L, meter.getVideoBitrate());
    }

    @Test
    public void aggregatesMuxedStreamWithoutAssigningItToATrack() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);
        meter.setMuxedStream(true);

        meter.recordMediaSample(C.TRACK_TYPE_DEFAULT, 2_750_000L, 0L, 4_000L);

        assertTrue(meter.isMuxedStream());
        assertEquals(5_500_000L, meter.getStreamBitrate());
        assertEquals(0L, meter.getVideoBitrate());
        assertEquals(0L, meter.getAudioBitrate());
    }

    @Test
    public void ignoresUnsupportedTracksAndInvalidSamples() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        meter.recordMediaSample(C.TRACK_TYPE_DEFAULT, 2_000_000L, 0L, 4_000L);
        meter.recordMediaSample(C.TRACK_TYPE_TEXT, 2_000_000L, 0L, 4_000L);
        meter.recordMediaSample(C.TRACK_TYPE_VIDEO, 0L, 0L, 4_000L);
        meter.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 4_000L, 4_000L);

        assertEquals(0L, meter.getVideoBitrate());
        assertEquals(0L, meter.getAudioBitrate());
        assertEquals(0L, meter.getStreamBitrate());
        assertFalse(meter.isMuxedStream());
    }

    @Test
    public void resetDropsSamplesFromPreviousPlayback() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);
        meter.setMuxedStream(true);
        meter.recordMediaSample(C.TRACK_TYPE_DEFAULT, 2_750_000L, 0L, 4_000L);
        meter.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 0L, 4_000L);
        meter.recordMediaSample(C.TRACK_TYPE_AUDIO, 80_000L, 0L, 4_000L);
        meter.recordFrameSample(0L, 30);
        meter.recordFrameSample(1_000L, 30);

        meter.reset();

        assertEquals(0L, meter.getVideoBitrate());
        assertEquals(0L, meter.getAudioBitrate());
        assertEquals(0L, meter.getStreamBitrate());
        assertEquals(0f, meter.getMeasuredFrameRate(), 0.01f);
        assertFalse(meter.isMuxedStream());
    }

    @Test
    public void estimatesFrameRateWhenStreamDoesNotDeclareIt() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        // The first batch establishes the time origin. Subsequent batches
        // represent 30 frames every second.
        meter.recordFrameSample(0L, 30);
        meter.recordFrameSample(500L, 15);
        meter.recordFrameSample(1_000L, 15);

        assertEquals(30f, meter.getMeasuredFrameRate(), 0.01f);
    }

    @Test
    public void estimatesFrameRateFromFramesSubmittedToRenderer() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        for (int frame = 0; frame <= 30; frame++) {
            meter.recordRenderedFrame(frame * 33_333_334L);
        }

        assertEquals(30f, meter.getMeasuredFrameRate(), 0.02f);
    }

    @Test
    public void estimatesSixtyFramesPerSecondWithoutCountingFirstFrameTwice() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        for (int frame = 0; frame <= 60; frame++) {
            meter.recordRenderedFrame(frame * 16_666_667L);
        }

        assertEquals(60f, meter.getMeasuredFrameRate(), 0.02f);
    }

    @Test
    public void renderedFrameMeasurementTakesPrecedenceOverProcessingBatches() {
        PlaybackBitrateMeter meter = new PlaybackBitrateMeter(null);

        meter.recordFrameSample(0L, 30);
        meter.recordFrameSample(1_000L, 30);
        for (int frame = 0; frame <= 60; frame++) {
            meter.recordRenderedFrame(frame * 16_666_667L);
        }

        assertEquals(60f, meter.getMeasuredFrameRate(), 0.02f);
    }

    @Test
    public void normalizesTypicalFrameRateGroups() {
        assertEquals(24f, PlaybackBitrateMeter.normalizeFrameRate(23.9f), 0.01f);
        assertEquals(30f, PlaybackBitrateMeter.normalizeFrameRate(29.9f), 0.01f);
        assertEquals(30f, PlaybackBitrateMeter.normalizeFrameRate(31.0f), 0.01f);
        assertEquals(50f, PlaybackBitrateMeter.normalizeFrameRate(50.8f), 0.01f);
        assertEquals(60f, PlaybackBitrateMeter.normalizeFrameRate(59.4f), 0.01f);
    }

    @Test
    public void preservesFrameRatesOutsideKnownGroups() {
        assertEquals(15f, PlaybackBitrateMeter.normalizeFrameRate(15f), 0.01f);
        assertEquals(70f, PlaybackBitrateMeter.normalizeFrameRate(70f), 0.01f);
        assertEquals(0f, PlaybackBitrateMeter.normalizeFrameRate(0f), 0.01f);
    }
}
