package cl.streambox.tv;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Process-wide OkHttp configuration shared with Media3 and resolver requests. */
public final class SharedHttpClient {
    private static final OkHttpClient INSTANCE = new OkHttpClient.Builder()
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
        }
        return builder.build();
    }
}
