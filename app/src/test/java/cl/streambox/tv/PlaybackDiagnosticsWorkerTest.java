package cl.streambox.tv;

import androidx.media3.common.C;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class PlaybackDiagnosticsWorkerTest {
    @Test public void calculatesFramesOnItsOwnThreadUsingOriginalRendererTimes() throws Exception {
        CountDownLatch measured = new CountDownLatch(1);
        AtomicReference<PlaybackDiagnosticsWorker> reference = new AtomicReference<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        try (PlaybackDiagnosticsWorker worker = new PlaybackDiagnosticsWorker(() -> {
            PlaybackDiagnosticsWorker current = reference.get();
            if (current != null && current.snapshot().measuredFrameRate > 0f) {
                callbackThread.set(Thread.currentThread().getName());
                measured.countDown();
            }
        })) {
            reference.set(worker);
            worker.setNotificationsEnabled(true);
            for (int index = 0; index <= 60; index++) worker.recordRenderedFrame(index * 16_666_667L);
            assertTrue(measured.await(5L, TimeUnit.SECONDS));
            assertEquals(60f, worker.snapshot().measuredFrameRate, 0.02f);
            assertEquals(60f, worker.snapshot().displayFrameRate, 0f);
            assertEquals("VibeM3U-Diagnostics", callbackThread.get());
            assertNotEquals(Thread.currentThread().getName(), callbackThread.get());
        }
    }

    @Test public void resetDiscardsQueuedSamplesAndFramesFromPreviousChannel() {
        ManualExecutor executor = new ManualExecutor();
        try (PlaybackDiagnosticsWorker worker = new PlaybackDiagnosticsWorker(null, executor)) {
            worker.recordMediaSample(C.TRACK_TYPE_VIDEO, 10_000_000L, 0L, 4_000L);
            for (int index = 0; index <= 60; index++) worker.recordRenderedFrame(index * 16_666_667L);
            worker.reset();
            assertFalse(worker.snapshot().hasRenderedVideoFrame);
            assertEquals(0L, worker.snapshot().videoBitrate);
            worker.recordMediaSample(C.TRACK_TYPE_VIDEO, 1_000_000L, 0L, 4_000L);
            for (int index = 0; index <= 30; index++) worker.recordRenderedFrame(index * 33_333_334L);
            executor.runPending();
            executor.tick();
            assertEquals(2_000_000L, worker.snapshot().videoBitrate);
            assertEquals(30f, worker.snapshot().measuredFrameRate, 0.02f);
        }
    }

    @Test public void deliversThrottledChangeEvenWithoutAnyFurtherValueChanges() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger notifications = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        try (PlaybackDiagnosticsWorker worker = new PlaybackDiagnosticsWorker(
                notifications::incrementAndGet, executor, clock::get)) {
            worker.setNotificationsEnabled(true);
            worker.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 0L, 4_000L);
            executor.runPending();
            assertEquals(1, notifications.get());
            worker.recordMediaSample(C.TRACK_TYPE_VIDEO, 4_000_000L, 4_000L, 8_000L);
            executor.runPending();
            assertEquals(6_000_000L, worker.snapshot().videoBitrate);
            assertEquals(1, notifications.get());
            clock.set(1_000_000_000L);
            executor.tick();
            assertEquals(2, notifications.get());
            executor.tick();
            assertEquals(2, notifications.get());
        }
    }

    @Test public void hiddenOsdKeepsLatestMeasurementsWithoutDispatchingUiWork() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger notifications = new AtomicInteger();
        try (PlaybackDiagnosticsWorker worker = new PlaybackDiagnosticsWorker(
                notifications::incrementAndGet, executor)) {
            worker.setMuxedStream(true);
            worker.recordMediaSample(C.TRACK_TYPE_DEFAULT, 2_750_000L, 0L, 4_000L);
            worker.recordRenderedFrame(0L);
            executor.runPending();
            executor.tick();
            assertEquals(0, notifications.get());
            assertTrue(worker.snapshot().hasRenderedVideoFrame);
            assertTrue(worker.snapshot().muxedStream);
            assertEquals(5_500_000L, worker.snapshot().streamBitrate);
            assertEquals(0L, worker.snapshot().videoBitrate);
            worker.setNotificationsEnabled(true);
            executor.tick();
            assertEquals(1, notifications.get());
        }
    }

    @Test public void segmentMeasurementsAloneDoNotExposePlaybackDiagnostics() {
        ManualExecutor executor = new ManualExecutor();
        try (PlaybackDiagnosticsWorker worker = new PlaybackDiagnosticsWorker(null, executor)) {
            worker.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 0L, 4_000L);
            executor.runPending();
            assertEquals(4_000_000L, worker.snapshot().videoBitrate);
            assertFalse(worker.snapshot().hasRenderedVideoFrame);
        }
    }

    @Test public void closingCancelsWorkerAndIgnoresLateCallbacks() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger notifications = new AtomicInteger();
        PlaybackDiagnosticsWorker worker = new PlaybackDiagnosticsWorker(notifications::incrementAndGet, executor);
        worker.setNotificationsEnabled(true);
        worker.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 0L, 4_000L);
        worker.close();
        worker.recordRenderedFrame(0L);
        worker.recordMediaSample(C.TRACK_TYPE_VIDEO, 2_000_000L, 0L, 4_000L);
        executor.runPending();
        executor.tick();
        assertTrue(executor.isShutdown());
        assertEquals(0, notifications.get());
        assertFalse(worker.snapshot().hasRenderedVideoFrame);
        assertEquals(0L, worker.snapshot().videoBitrate);
    }

    /** Deterministic scheduling for cancellation, backpressure and channel-generation tests. */
    private static final class ManualExecutor extends AbstractExecutorService implements ScheduledExecutorService {
        private final Queue<Runnable> pending = new ArrayDeque<>();
        private TickFuture periodic;
        private boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            pending.add(command);
        }

        void runPending() {
            while (!pending.isEmpty()) pending.remove().run();
        }

        void tick() {
            if (periodic != null && !periodic.isCancelled() && !shutdown) periodic.command.run();
        }

        @Override public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            periodic = new TickFuture(command);
            return periodic;
        }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public void shutdown() { shutdown = true; }

        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> abandoned = new ArrayList<>(pending);
            pending.clear();
            return abandoned;
        }

        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }

    private static final class TickFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Runnable command;
        TickFuture(Runnable command) { super(command, null); this.command = command; }
        @Override public long getDelay(TimeUnit unit) { return 0L; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
