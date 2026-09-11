package cl.streambox.tv;

import androidx.media3.common.Player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IntermittentBufferingDetectorTest {
    @Test public void requiresFiveRealCyclesInsideFiveSeconds() {
        IntermittentBufferingDetector detector = new IntermittentBufferingDetector();
        detector.onPlaybackStateChanged(Player.STATE_READY, true, true, 0L);

        for (int index = 0; index < 5; index++) {
            long bufferingAt = 500L + index * 800L;
            detector.onPlaybackStateChanged(Player.STATE_BUFFERING, true, true, bufferingAt);
            detector.onPlaybackStateChanged(Player.STATE_READY, true, true, bufferingAt + 100L);
        }

        assertFalse(detector.shouldRecover(4_000L, false));
        assertTrue(detector.shouldRecover(4_000L, true));
    }

    @Test public void startupAndRepeatedBufferingCallbacksDoNotCountCycles() {
        IntermittentBufferingDetector detector = new IntermittentBufferingDetector();
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, true, false, 0L);
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, true, false, 100L);
        detector.onPlaybackStateChanged(Player.STATE_READY, true, false, 200L);
        assertFalse(detector.shouldRecover(5_000L, true));

        detector.onPlaybackStateChanged(Player.STATE_READY, true, true, 6_000L);
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, true, true, 6_100L);
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, true, true, 6_200L);
        detector.onPlaybackStateChanged(Player.STATE_READY, true, true, 6_300L);
        assertFalse(detector.shouldRecover(6_400L, true));
    }

    @Test public void pauseClearsAnIncompleteEpisodeAndStablePlaybackResetsIt() {
        IntermittentBufferingDetector detector = new IntermittentBufferingDetector();
        detector.onPlaybackStateChanged(Player.STATE_READY, true, true, 0L);
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, true, true, 100L);
        detector.onPlaybackStateChanged(Player.STATE_READY, true, true, 200L);
        detector.onPlaybackStateChanged(Player.STATE_READY, false, true, 300L);
        assertFalse(detector.shouldRecover(400L, true));

        detector.onPlaybackStateChanged(Player.STATE_READY, true, true, 1_000L);
        for (int index = 0; index < 5; index++) {
            long at = 1_100L + index * 700L;
            detector.onPlaybackStateChanged(Player.STATE_BUFFERING, true, true, at);
            detector.onPlaybackStateChanged(Player.STATE_READY, true, true, at + 50L);
        }
        detector.onStablePlayback(9_000L, true);
        assertFalse(detector.shouldRecover(9_000L, true));
    }
}
