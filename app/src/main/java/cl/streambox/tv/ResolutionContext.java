package cl.streambox.tv;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;

/**
 * Cancellation and monotonic deadline shared by one playback resolution.
 *
 * <p>The context is deliberately independent of an executor. A resolver can
 * activate it on each worker thread, while an owner can cancel it from the UI
 * thread. Child contexts inherit the parent's cancellation and the earlier of
 * both deadlines. Cancelling a child only cancels work registered with that
 * child; the parent and its other children remain usable.</p>
 */
public final class ResolutionContext {
    private static final ThreadLocal<ResolutionContext> CURRENT = new ThreadLocal<>();
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final ResolutionContext parent;
    private final long deadlineNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<ResolutionContext> children =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CallRegistration> calls =
            new CopyOnWriteArrayList<>();
    private final ManifestHandoffCache manifestCache;
    private final boolean ownsManifestCache;

    /** Creates a context whose total budget is {@code timeoutMs} milliseconds. */
    public ResolutionContext(long timeoutMs) {
        this(timeoutMs, null, null);
    }

    private ResolutionContext(
            long timeoutMs,
            ResolutionContext parent,
            ManifestHandoffCache inheritedManifestCache
    ) {
        this.parent = parent;
        long localDeadline = deadlineFromNow(timeoutMs);
        this.deadlineNanos = parent == null
                ? localDeadline
                : Math.min(localDeadline, parent.deadlineNanos);
        this.ownsManifestCache = inheritedManifestCache == null;
        this.manifestCache = ownsManifestCache
                ? new ManifestHandoffCache(this)
                : inheritedManifestCache;
        if (parent != null) {
            parent.children.add(this);
            if (parent.isCancelled()) cancel();
        }
    }

    /** Returns the context active on this thread, or {@code null}. */
    public static ResolutionContext current() {
        return CURRENT.get();
    }

    /**
     * Installs this context on the current thread until the returned scope is
     * closed. {@link Scope#close()} intentionally declares no checked
     * exception so it is safe in try-with-resources blocks.
     */
    public Scope activate() {
        ResolutionContext previous = CURRENT.get();
        CURRENT.set(this);
        return new Scope(this, previous);
    }

    /** Creates an independently cancellable child budget under this context. */
    public ResolutionContext child(long timeoutMs) {
        return new ResolutionContext(timeoutMs, this, manifestCache);
    }

    /** Throws when this context, its parent, its deadline, or the thread is cancelled. */
    public void check() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Solicitud cancelada.");
        }
        if (isCancelled()) {
            throw new IOException("Solicitud cancelada.");
        }
        if (remainingMillis() <= 0L) {
            throw new IOException("Tiempo de resolución agotado.");
        }
    }

    /** Returns the remaining total budget in milliseconds, or zero when exhausted. */
    public long remainingMillis() {
        if (isCancelled()) return 0L;
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) return 0L;
        long millis = remaining / NANOS_PER_MILLISECOND;
        // A positive sub-millisecond budget is still usable by an API that
        // accepts integer milliseconds; report it as one millisecond.
        return Math.max(1L, millis);
    }

    /** Returns true after this context or an ancestor has been cancelled. */
    public boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    /** Cancels this context, its descendants, and all registered OkHttp calls. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        if (ownsManifestCache) manifestCache.clear();
        for (CallRegistration registration : calls) registration.cancelFromContext();
        for (ResolutionContext child : children) child.cancel();
    }

    /** Returns the per-resolution handoff cache shared by this context's children. */
    public ManifestHandoffCache manifests() {
        return manifestCache;
    }

    /**
     * Registers an OkHttp call for cancellation. The returned registration is
     * idempotent and has a no-throws {@code close()} method; callers should
     * close it in a finally block after the synchronous call completes.
     */
    public Registration register(Call call) {
        Objects.requireNonNull(call, "call");
        CallRegistration registration = new CallRegistration(this, call);
        if (isCancelled()) {
            registration.cancelFromContext();
            return registration;
        }
        calls.add(registration);
        if (isCancelled() && calls.remove(registration)) registration.cancelFromContext();
        return registration;
    }

    /** Descriptive alias for callers that prefer an explicit API name. */
    public Registration registerCall(Call call) {
        return register(call);
    }

    /**
     * Captures the current context and restores it while the callable runs on
     * another thread. A callable created outside a context is returned as-is.
     */
    public static <T> Callable<T> wrapCurrent(Callable<T> callable) {
        Objects.requireNonNull(callable, "callable");
        ResolutionContext captured = current();
        if (captured == null) return callable;
        return () -> {
            try (Scope ignored = captured.activate()) {
                captured.check();
                return callable.call();
            }
        };
    }

    private static long deadlineFromNow(long timeoutMs) {
        long safeTimeout = Math.max(0L, timeoutMs);
        long now = System.nanoTime();
        if (safeTimeout > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            return Long.MAX_VALUE;
        }
        long delta = safeTimeout * NANOS_PER_MILLISECOND;
        if (Long.MAX_VALUE - now < delta) return Long.MAX_VALUE;
        return now + delta;
    }

    /** No-throws AutoCloseable scope returned by {@link #activate()}. */
    public static final class Scope implements AutoCloseable {
        private final ResolutionContext context;
        private final ResolutionContext previous;
        private boolean closed;

        private Scope(ResolutionContext context, ResolutionContext previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            // Do not overwrite a scope installed by unrelated code after an
            // out-of-order close. Normal nested scopes restore exactly.
            if (CURRENT.get() == context) CURRENT.set(previous);
        }
    }

    /** AutoCloseable registration whose close method has no checked exception. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class CallRegistration implements Registration {
        private final ResolutionContext owner;
        private final Call call;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        CallRegistration(ResolutionContext owner, Call call) {
            this.owner = owner;
            this.call = call;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            owner.calls.remove(this);
        }

        void cancelFromContext() {
            if (!closed.get()) call.cancel();
        }
    }
}
