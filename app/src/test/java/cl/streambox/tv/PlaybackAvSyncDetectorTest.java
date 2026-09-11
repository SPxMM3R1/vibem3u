package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackAvSyncDetectorTest {
    @Test public void detectsVideoStallOnlyWhenAudioAndPositionContinue() {
        PlaybackAvSyncDetector detector = new PlaybackAvSyncDetector();
        assertEquals(PlaybackAvSyncDetector.Signal.NONE, detector.sample(
                0L, 0L, true, false, true, 0L, true, true, 0L, 0L));
        assertEquals(PlaybackAvSyncDetector.Signal.NONE, detector.sample(
                1_000L, 1_000_000_000L, true, false, true, 0L, true, true, 1_000L, 0L));
        assertEquals(PlaybackAvSyncDetector.Signal.VIDEO_STALLED_WITH_AUDIO,
                detector.sample(
                        3_100L, 3_100_000_000L, true, false, true, 0L,
                        true, true, 2_000L, 0L
                ));
    }

    @Test public void detectsRepeatedAudioUnderrunsWithFreshVideo() {
        PlaybackAvSyncDetector detector = new PlaybackAvSyncDetector();
        detector.sample(0L, 0L, true, false, true, 0L, true, true, 0L, 0L);
        detector.sample(500L, 500_000_000L, true, false, true, 500_000_000L,
                true, true, 500L, 1L);
        assertEquals(PlaybackAvSyncDetector.Signal.AUDIO_UNDERRUN_WITH_VIDEO,
                detector.sample(1_000L, 1_000_000_000L, true, false, true,
                        1_000_000_000L, true, true, 1_000L, 2L));
    }

    @Test public void bufferingAndPausedPlaybackNeverLookLikeAvMismatch() {
        PlaybackAvSyncDetector detector = new PlaybackAvSyncDetector();
        detector.sample(0L, 0L, true, false, true, 0L, true, true, 0L, 0L);
        assertEquals(PlaybackAvSyncDetector.Signal.NONE,
                detector.sample(3_000L, 3_000_000_000L, true, true, true, 0L,
                        true, true, 0L, 2L));
        assertEquals(PlaybackAvSyncDetector.Signal.NONE,
                detector.sample(4_000L, 4_000_000_000L, false, false, true, 0L,
                        true, true, 0L, 4L));
    }

    @Test public void realProgressRequiresBothVideoAndPositionToBeStalled() {
        PlaybackAvSyncDetector detector = new PlaybackAvSyncDetector();
        detector.sample(0L, 0L, true, false, true, 0L, true, true, 0L, 0L);
        detector.sample(1_000L, 1_000_000_000L, true, false, true, 0L,
                true, true, 1_000L, 0L);
        assertFalse(detector.isRealProgressStalled(2_000L, true));
        detector.sample(2_000L, 2_000_000_000L, true, false, true, 0L,
                true, true, 1_000L, 0L);
        assertTrue(detector.isRealProgressStalled(4_000L, true));
    }
}
