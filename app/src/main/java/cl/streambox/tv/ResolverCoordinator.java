package cl.streambox.tv;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Session-only cache and single-flight coordination for dynamic sources. */
public final class ResolverCoordinator {
    private final Object lock = new Object();
    private final Map<String, ResolvedPlaybackSource> memoryCache = new HashMap<>();
    private final Map<String, InFlight> inFlight = new HashMap<>();

    public ResolvedPlaybackSource resolve(
            Channel channel,
            StreamResolver resolver,
            boolean forceRefresh
    ) throws IOException {
        String key = key(channel, resolver);
        InFlight owner = new InFlight();
        InFlight existing;
        synchronized (lock) {
            if (forceRefresh) {
                memoryCache.remove(key);
                InFlight stale = inFlight.remove(key);
                if (stale != null) stale.cancel();
            }
            if (!forceRefresh && resolver.cacheTtlMillis() > 0L) {
                ResolvedPlaybackSource cached = memoryCache.get(key);
                if (cached != null && !cached.isExpired(System.currentTimeMillis())) return cached;
                if (cached != null) memoryCache.remove(key);
            }
            existing = inFlight.get(key);
            if (existing == null) inFlight.put(key, owner);
        }
        if (existing != null) return await(existing);

        try {
            ResolvedPlaybackSource resolved = resolver.resolve(channel);
            if (resolver.cacheTtlMillis() > 0L && resolved != null) {
                synchronized (lock) {
                    if (inFlight.get(key) == owner) memoryCache.put(key, resolved);
                }
            }
            owner.complete(resolved);
            return resolved;
        } catch (Throwable error) {
            owner.completeExceptionally(error);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("No se pudo resolver la fuente.", error);
        } finally {
            synchronized (lock) {
                if (inFlight.get(key) == owner) inFlight.remove(key);
            }
        }
    }

    public void invalidate(Channel channel, StreamResolver resolver) {
        if (channel == null || resolver == null) return;
        synchronized (lock) {
            String key = key(channel, resolver);
            memoryCache.remove(key);
            InFlight stale = inFlight.remove(key);
            if (stale != null) stale.cancel();
        }
    }

    public void clear() {
        synchronized (lock) {
            memoryCache.clear();
            for (InFlight request : inFlight.values()) request.cancel();
            inFlight.clear();
        }
    }

    int cachedSourceCount() {
        synchronized (lock) {
            return memoryCache.size();
        }
    }

    private static ResolvedPlaybackSource await(
            InFlight request
    ) throws IOException {
        return request.await();
    }

    private static String key(Channel channel, StreamResolver resolver) {
        String stable = resolver.stableSourceId(channel);
        return resolver.getId() + ":" + (stable == null ? "" : stable);
    }

    private static final class InFlight {
        private ResolvedPlaybackSource result;
        private Throwable error;
        private boolean done;

        synchronized void complete(ResolvedPlaybackSource value) {
            if (done) return;
            result = value;
            done = true;
            notifyAll();
        }

        synchronized void completeExceptionally(Throwable value) {
            if (done) return;
            error = value;
            done = true;
            notifyAll();
        }

        synchronized void cancel() {
            completeExceptionally(new IOException("Solicitud cancelada."));
        }

        synchronized ResolvedPlaybackSource await() throws IOException {
            while (!done) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", interrupted);
                }
            }
            if (error instanceof IOException) throw (IOException) error;
            if (error != null) throw new IOException("No se pudo resolver la fuente.", error);
            return result;
        }
    }
}
