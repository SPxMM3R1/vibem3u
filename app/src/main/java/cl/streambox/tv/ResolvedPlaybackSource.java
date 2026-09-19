package cl.streambox.tv;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A playback URL plus the request headers needed for that particular source. */
public final class ResolvedPlaybackSource {
    private final URI playbackUri;
    private final Map<String, String> requestHeaders;
    private final String userAgent;
    private final String resolverId;
    private final String stableSourceId;
    /** Non-secret provider variant, for example the TvVoo alias that won. */
    private final String variantId;
    private final String mimeType;
    private final URI mediaFlowOrigin;
    private final long expiresAtMillis;
    private final boolean dynamicallyResolved;

    private ResolvedPlaybackSource(
            URI playbackUri,
            Map<String, String> requestHeaders,
            String userAgent,
            String resolverId,
            String stableSourceId,
            String variantId,
            String mimeType,
            URI mediaFlowOrigin,
            long expiresAtMillis,
            boolean dynamicallyResolved
    ) {
        this.playbackUri = Objects.requireNonNull(playbackUri, "playbackUri");
        this.requestHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<>(requestHeaders == null
                        ? Collections.emptyMap()
                        : requestHeaders)
        );
        this.userAgent = userAgent == null ? "" : userAgent;
        this.resolverId = resolverId == null || AppStrings.isBlank(resolverId) ? null : resolverId;
        this.stableSourceId = stableSourceId == null || AppStrings.isBlank(stableSourceId)
                ? null
                : stableSourceId;
        this.variantId = variantId == null || AppStrings.isBlank(variantId)
                ? null
                : variantId;
        this.mimeType = mimeType == null || AppStrings.isBlank(mimeType)
                ? null
                : mimeType.trim();
        this.mediaFlowOrigin = mediaFlowOrigin;
        this.expiresAtMillis = Math.max(0L, expiresAtMillis);
        this.dynamicallyResolved = dynamicallyResolved;
    }

    public static ResolvedPlaybackSource direct(Channel channel, String userAgent) {
        Objects.requireNonNull(channel, "channel");
        return new ResolvedPlaybackSource(
                channel.getStreamUri(),
                ChannelRequestHeaders.from(channel),
                ChannelRequestHeaders.userAgent(channel, userAgent),
                null,
                null,
                null,
                null,
                null,
                0L,
                false
        );
    }

    public static ResolvedPlaybackSource dynamic(
            String resolverId,
            URI playbackUri,
            Map<String, String> requestHeaders,
            String userAgent
    ) {
        return new ResolvedPlaybackSource(
                playbackUri,
                requestHeaders,
                userAgent,
                resolverId,
                null,
                null,
                null,
                null,
                0L,
                true
        );
    }

    public static ResolvedPlaybackSource dynamic(
            String resolverId,
            String stableSourceId,
            URI playbackUri,
            Map<String, String> requestHeaders,
            String userAgent,
            long expiresAtMillis
    ) {
        return dynamic(
                resolverId,
                stableSourceId,
                playbackUri,
                requestHeaders,
                userAgent,
                expiresAtMillis,
                null
        );
    }

    public static ResolvedPlaybackSource dynamic(
            String resolverId,
            String stableSourceId,
            URI playbackUri,
            Map<String, String> requestHeaders,
            String userAgent,
            long expiresAtMillis,
            String variantId
    ) {
        return new ResolvedPlaybackSource(
                playbackUri,
                requestHeaders,
                userAgent,
                resolverId,
                stableSourceId,
                variantId,
                null,
                null,
                expiresAtMillis,
                true
        );
    }

    /** Dynamic source with explicit MIME and a trusted MediaFlow origin. */
    public static ResolvedPlaybackSource dynamic(
            String resolverId,
            String stableSourceId,
            URI playbackUri,
            Map<String, String> requestHeaders,
            String userAgent,
            long expiresAtMillis,
            String variantId,
            String mimeType,
            URI mediaFlowOrigin
    ) {
        return new ResolvedPlaybackSource(
                playbackUri,
                requestHeaders,
                userAgent,
                resolverId,
                stableSourceId,
                variantId,
                mimeType,
                mediaFlowOrigin,
                expiresAtMillis,
                true
        );
    }

    public static ResolvedPlaybackSource fallback(
            Channel channel,
            String resolverId,
            String userAgent
    ) {
        Objects.requireNonNull(channel, "channel");
        return new ResolvedPlaybackSource(
                channel.getStreamUri(),
                ChannelRequestHeaders.from(channel),
                ChannelRequestHeaders.userAgent(channel, userAgent),
                resolverId,
                null,
                null,
                null,
                null,
                0L,
                false
        );
    }

    public URI getPlaybackUri() {
        return playbackUri;
    }

    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getResolverId() {
        return resolverId;
    }

    public boolean hasResolver() {
        return resolverId != null;
    }

    public String getStableSourceId() {
        return stableSourceId == null ? "" : stableSourceId;
    }

    /**
     * Returns a safe, provider-specific variant identifier. It is never a URL
     * and may be persisted by an alias-learning store when appropriate.
     */
    public String getVariantId() {
        return variantId == null ? "" : variantId;
    }

    /** Explicit media type, for example application/x-mpegURL or video/mp2t. */
    public String getMimeType() {
        return mimeType == null ? "" : mimeType;
    }

    public boolean hasMimeType() {
        return mimeType != null;
    }

    /** Trusted origin used by MediaFlow extraction and playback policy. */
    public URI getMediaFlowOriginUri() {
        return mediaFlowOrigin;
    }

    public String getMediaFlowOrigin() {
        return mediaFlowOrigin == null ? "" : mediaFlowOrigin.toString();
    }

    public boolean isMediaFlow() {
        return mediaFlowOrigin != null;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
    }

    public boolean isDynamicallyResolved() {
        return dynamicallyResolved;
    }
}
