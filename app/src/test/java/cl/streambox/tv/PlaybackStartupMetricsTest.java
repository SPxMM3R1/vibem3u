package cl.streambox.tv;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class PlaybackStartupMetricsTest {
    @Test public void stagesAreMonotonicAndOldChannelCannotSupplyTheFirstFrame() {
        AtomicLong clock = new AtomicLong();
        PlaybackStartupMetrics metrics = new PlaybackStartupMetrics(clock::get);
        long old = metrics.begin("tvvoo", PlaybackStartupMetrics.Reason.CHANNEL);
        clock.set(100_000_000L);
        long current = metrics.begin("tvn", PlaybackStartupMetrics.Reason.CHANNEL);
        clock.set(150_000_000L); metrics.dequeued(current);
        clock.set(400_000_000L); metrics.resolved(current);
        assertFalse(metrics.firstFrame(old));
        clock.set(600_000_000L); metrics.manifest(current);
        clock.set(800_000_000L); metrics.segment(current);
        clock.set(1_000_000_000L); assertTrue(metrics.firstFrame(current));
        assertFalse(metrics.firstFrame(current));
        assertEquals(50L, metrics.snapshot().queueMs);
        assertEquals(300L, metrics.snapshot().resolvedMs);
        assertEquals(900L, metrics.snapshot().firstFrameMs);
        assertEquals("cancelled", metrics.samples().get(0).outcome);
    }

    @Test public void summariesDistinguishFailedAndCancelledAndCountRebuffering() {
        AtomicLong clock = new AtomicLong();
        PlaybackStartupMetrics metrics = new PlaybackStartupMetrics(clock::get);
        long first = metrics.begin("tvvoo", PlaybackStartupMetrics.Reason.CHANNEL);
        clock.set(1_000_000_000L); metrics.firstFrame(first);
        metrics.buffering(first, true);
        clock.set(1_700_000_000L); metrics.buffering(first, false);
        assertEquals(700L, metrics.snapshot().rebufferMs);
        assertEquals(1, metrics.snapshot().rebuffers);
        long failed = metrics.begin("tvvoo", PlaybackStartupMetrics.Reason.REFRESH);
        metrics.failed(failed);
        metrics.begin("tvvoo", PlaybackStartupMetrics.Reason.CHANNEL);
        metrics.finish();
        String summary = metrics.summary("tvvoo");
        assertTrue(summary.contains("opened=1 failed=1 cancelled=1"));
        assertTrue(summary.contains("p50Ms=1000 p95Ms=1000"));
    }

    @Test public void historyIsBoundedAndProviderCannotExposeAUrl() {
        PlaybackStartupMetrics metrics = new PlaybackStartupMetrics();
        for (int i = 0; i < 200; i++) {
            metrics.begin("https://example.org/?token=synthetic", PlaybackStartupMetrics.Reason.CHANNEL);
        }
        assertTrue(metrics.samples().size() <= 129);
        assertEquals("direct", metrics.snapshot().provider);
        assertFalse(metrics.snapshot().safeSummary().contains("synthetic"));
    }
}
