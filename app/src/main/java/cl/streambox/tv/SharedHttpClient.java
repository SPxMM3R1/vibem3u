package cl.streambox.tv;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Process-wide OkHttp configuration shared with Media3 and resolver requests. */
public final class SharedHttpClient {
    /** Same DNS strategy identity preserves OkHttp pooling across resolution contexts. */
    private static final class ScopedDns implements okhttp3.Dns {
        private final ResolutionContext context;
        ScopedDns(ResolutionContext context) { this.context = context; }
        @Override public java.util.List<java.net.InetAddress> lookup(String host) throws java.net.UnknownHostException {
            if (context == null) return okhttp3.Dns.SYSTEM.lookup(host);
            try { return context.lookupDns(host, okhttp3.Dns.SYSTEM); }
            catch (java.io.IOException failed) {
                throw new java.net.UnknownHostException("DNS del resolutor no disponible.");
            }
        }
        @Override public boolean equals(Object other) { return other instanceof ScopedDns; }
        @Override public int hashCode() { return ScopedDns.class.hashCode(); }
    }
    private static final OkHttpClient INSTANCE = new OkHttpClient.Builder()
            .dns(new ScopedDns(null))
            // Media3 follows redirects itself through the supplied client. The
            // resolver path derives a client with both flags disabled so it can
            // validate every hop before opening it.
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(12_000L, TimeUnit.MILLISECONDS)
            .readTimeout(20_000L, TimeUnit.MILLISECONDS)
            .writeTimeout(20_000L, TimeUnit.MILLISECONDS)
            .build();

    /** User-Agent de la app (reproductor, logos y guía); antes decía «0.4.42» fijo. */
    static final String USER_AGENT = "VibeM3U/" + BuildConfig.VERSION_NAME + " (Android TV)";

    private SharedHttpClient() {}

    /** Returns the singleton client intended for {@code OkHttpDataSource.Factory}. */
    public static OkHttpClient get() {
        return INSTANCE;
    }

    /** Alias useful to code that reads as a factory rather than a singleton accessor. */
    public static OkHttpClient client() {
        return INSTANCE;
    }

    /**
     * Releases process-wide HTTP work during an explicit app exit. This must
     * not be called from a normal activity recreation because the singleton is
     * reused by the next activity in the same process.
     */
    static void shutdownForProcessExit() {
        INSTANCE.dispatcher().cancelAll();
        INSTANCE.connectionPool().evictAll();
        INSTANCE.dispatcher().executorService().shutdownNow();
    }

    /**
     * Creates a short-lived client sharing the singleton's dispatcher, pool,
     * cache and TLS configuration while applying a per-attempt deadline.
     */
    static OkHttpClient forResolution(
            ResolutionContext context,
            int connectTimeoutMs,
            int readTimeoutMs,
            int writeTimeoutMs,
            boolean followRedirects
    ) {
        OkHttpClient.Builder builder = INSTANCE.newBuilder()
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects)
                .connectTimeout(Math.max(1L, connectTimeoutMs), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1L, readTimeoutMs), TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1L, writeTimeoutMs), TimeUnit.MILLISECONDS);
        if (context != null) {
            builder.callTimeout(Math.max(1L, context.remainingMillis()), TimeUnit.MILLISECONDS);
            // Connect to the same per-attempt addresses checked by PublicStreamPolicy.
            builder.dns(new ScopedDns(context));
        }
        return builder.build();
    }
}
