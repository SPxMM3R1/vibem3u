package cl.streambox.tv;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Monotonic, process-local budget shared by one resolver attempt. */
final class ResolutionDeadline {
    private final long deadlineNanos;

    ResolutionDeadline(long timeoutMillis) {
        long safeTimeout = Math.max(1L, timeoutMillis);
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safeTimeout);
    }

    boolean isExpired() {
        return System.nanoTime() >= deadlineNanos;
    }

    long remainingMillis() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) return 0L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    void check() throws IOException {
        if (Thread.currentThread().isInterrupted() || isExpired()) {
            throw new IOException("Tiempo de resolución agotado.");
        }
    }
}
