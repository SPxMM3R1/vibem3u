package cl.streambox.tv;

/**
 * Separates bounded in-process reuse age from an explicit provider expiry.
 *
 * <p>Providers often expose an access token without a lifetime. This policy
 * therefore never decodes or infers a JWT: an explicit expiry is honoured when
 * supplied, otherwise reuse is bounded by a short age. The safety margin is
 * subtracted from explicit expiry so Media3 does not begin a request at the
 * exact instant a provider invalidates the token.</p>
 */
public final class TokenExpiryPolicy {
    public static final long DEFAULT_MAX_REUSE_AGE_MILLIS = 5L * 60L * 1000L;
    public static final long DEFAULT_EXPIRY_MARGIN_MILLIS = 15_000L;

    private final long maxReuseAgeMillis;
    private final long expiryMarginMillis;

    public TokenExpiryPolicy() {
        this(DEFAULT_MAX_REUSE_AGE_MILLIS, DEFAULT_EXPIRY_MARGIN_MILLIS);
    }

    public TokenExpiryPolicy(long maxReuseAgeMillis) {
        this(maxReuseAgeMillis, DEFAULT_EXPIRY_MARGIN_MILLIS);
    }

    public TokenExpiryPolicy(long maxReuseAgeMillis, long expiryMarginMillis) {
        this.maxReuseAgeMillis = Math.max(0L, maxReuseAgeMillis);
        this.expiryMarginMillis = Math.max(0L, expiryMarginMillis);
    }

    public long getMaxReuseAgeMillis() {
        return maxReuseAgeMillis;
    }

    public long getExpiryMarginMillis() {
        return expiryMarginMillis;
    }

    /**
     * Computes the safe expiry timestamp from fetch time and optional provider
     * metadata. A value of zero means that no explicit expiry was available.
     */
    public long effectiveExpiryAtMillis(long fetchedAtMillis, long explicitExpiryAtMillis) {
        long fetched = Math.max(0L, fetchedAtMillis);
        long ageExpiry = saturatingAdd(fetched, maxReuseAgeMillis);
        long explicitExpiry = safeExplicitExpiry(explicitExpiryAtMillis);
        if (explicitExpiry == 0L) return ageExpiry;
        long guardedExplicit = explicitExpiry <= expiryMarginMillis
                ? 0L
                : explicitExpiry - expiryMarginMillis;
        return Math.min(ageExpiry, guardedExplicit);
    }

    /** Alias that reads naturally at resolver call sites. */
    public long expiresAtMillis(long fetchedAtMillis, long explicitExpiryAtMillis) {
        return effectiveExpiryAtMillis(fetchedAtMillis, explicitExpiryAtMillis);
    }

    public boolean isExpired(
            long fetchedAtMillis,
            long explicitExpiryAtMillis,
            long nowMillis
    ) {
        long expiry = effectiveExpiryAtMillis(fetchedAtMillis, explicitExpiryAtMillis);
        return expiry == 0L || Math.max(0L, nowMillis) >= expiry;
    }

    public boolean canReuse(
            long fetchedAtMillis,
            long explicitExpiryAtMillis,
            long nowMillis
    ) {
        return !isExpired(fetchedAtMillis, explicitExpiryAtMillis, nowMillis);
    }

    public boolean canReuse(long fetchedAtMillis, long explicitExpiryAtMillis) {
        return canReuse(fetchedAtMillis, explicitExpiryAtMillis, System.currentTimeMillis());
    }

    private static long safeExplicitExpiry(long value) {
        return value > 0L ? value : 0L;
    }

    private static long saturatingAdd(long first, long second) {
        if (second <= 0L) return first;
        if (Long.MAX_VALUE - first < second) return Long.MAX_VALUE;
        return first + second;
    }
}
