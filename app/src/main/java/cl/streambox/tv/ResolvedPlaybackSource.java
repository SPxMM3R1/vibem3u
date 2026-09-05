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
    private final long expiresAtMillis;
    private final boolean dynamicallyResolved;

    private ResolvedPlaybackSource(
            URI playbackUri,
            Map<String, String> requestHeaders,
            String userAgent,
            String resolverId,
            String stableSourceId,
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
        this.resolverId = resolverId == null || resolverId.isBlank() ? null : resolverId;
        this.stableSourceId = stableSourceId == null || stableSourceId.isBlank()
                ? null
                : stableSourceId;
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
        return new ResolvedPlaybackSource(
                playbackUri,
                requestHeaders,
                userAgent,
                resolverId,
                stableSourceId,
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
