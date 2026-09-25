package cl.streambox.tv;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Session-only cache and single-flight coordination for dynamic sources. */
public final class ResolverCoordinator {
    private final Object lock = new Object();
    private final Map<String, CachedSource> memoryCache = new HashMap<>();
    private final Map<String, InFlight> inFlight = new HashMap<>();

    public ResolvedPlaybackSource resolve(
            Channel channel,
            StreamResolver resolver,
            boolean forceRefresh
    ) throws IOException {
        return resolve(channel, resolver, forceRefresh, ResolutionProgressListener.NONE);
    }

    public ResolvedPlaybackSource resolve(
            Channel channel,
            StreamResolver resolver,
            boolean forceRefresh,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        String key = key(channel, resolver);
        boolean allowCache = shouldCache(resolver);
        InFlight owner = new InFlight();
        InFlight existing;
        synchronized (lock) {
            if (forceRefresh) {
                memoryCache.remove(key);
                InFlight stale = inFlight.remove(key);
                if (stale != null) stale.cancel();
            }
            if (!allowCache) {
                // A resolver can be upgraded from a cached implementation to
                // a token-producing one during the same process. Remove any
                // legacy entry before it can be observed by playback.
                memoryCache.remove(key);
            }
            if (!forceRefresh && allowCache) {
                CachedSource cachedEntry = memoryCache.get(key);
                ResolvedPlaybackSource cached = cachedEntry == null
                        ? null
                        : cachedEntry.source;
                if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.CACHE_REUSED,
                            "MEMORIA · clave=" + key + " · GET "
                                    + SafePlaybackText.url(cached.getPlaybackUri())
                    ));
                    return cached;
                }
                if (cached != null) memoryCache.remove(key);
            }
            existing = inFlight.get(key);
            if (existing == null) inFlight.put(key, owner);
        }
        if (existing != null) {
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.SOURCE_REQUEST,
                    "resolución compartida · esperando solicitud en curso"
            ));
            return await(existing);
        }

        try {
            ResolvedPlaybackSource resolved = resolver.resolve(channel, progress);
            ResolutionContext context = ResolutionContext.current();
            if (context != null) context.check();
            if (allowCache
                    && resolved != null
                    && !resolved.isExpired(System.currentTimeMillis())) {
                synchronized (lock) {
                    if (inFlight.get(key) == owner) {
                        memoryCache.put(key, new CachedSource(
                                resolved,
                                resolver.keepSessionSourceOnPlaybackPause()
                        ));
                    }
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

    /**
     * Ends the active playback while retaining only explicitly opted-in
     * session tokens. Other temporary resolver URLs are discarded as before.
     */
    public void clearForPlaybackPause() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, CachedSource>> iterator =
                    memoryCache.entrySet().iterator();
            while (iterator.hasNext()) {
                CachedSource cached = iterator.next().getValue();
                if (!cached.keepOnPlaybackPause || cached.source.isExpired(now)) {
                    iterator.remove();
                }
            }
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

    private static boolean shouldCache(StreamResolver resolver) {
        return resolver != null
                && resolver.cacheResolvedSource()
                && resolver.cacheTtlMillis() > 0L;
    }

    private static final class CachedSource {
        private final ResolvedPlaybackSource source;
        private final boolean keepOnPlaybackPause;

        CachedSource(ResolvedPlaybackSource source, boolean keepOnPlaybackPause) {
            this.source = source;
            this.keepOnPlaybackPause = keepOnPlaybackPause;
        }
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
                    ResolutionContext context = ResolutionContext.current();
                    if (context != null) {
                        context.check();
                        wait(Math.max(1L, Math.min(100L, context.remainingMillis())));
                    } else {
                        wait();
                    }
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
