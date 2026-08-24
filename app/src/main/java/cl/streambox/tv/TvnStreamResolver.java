package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves TVN's short-lived MediaStream token from its public live page. */
public final class TvnStreamResolver implements StreamResolver {
    public static final String ID = "tvn";
    private static final String TVG_ID = "0104";
    private static final String LIVE_PAGE = "https://live.tvn.cl/";
    private static final String PLAYLIST_BASE = "https://mdstrm.com/live-stream-playlist/";

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;

    public TvnStreamResolver() {
        this(null, new TokenHttpClient());
    }

    public TvnStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient());
    }

    TvnStreamResolver(TokenHttpClient httpClient) {
        this(null, httpClient);
    }

    private TvnStreamResolver(ResolverDefinition definition, TokenHttpClient httpClient) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.validator = new HlsStreamValidator(httpClient);
    }

    @Override
    public String getId() {
        return definition == null ? ID : definition.getId();
    }

    @Override
    public boolean supports(Channel channel) {
        if (definition != null) {
            return definition.matchesExplicit(channel) || definition.matchesTvgId(channel);
        }
        return channel != null && TVG_ID.equalsIgnoreCase(channel.getTvgId().trim());
    }

    @Override public String stableSourceId(Channel channel) {
        return definition == null ? StreamResolver.super.stableSourceId(channel)
                : definition.stableSourceId(channel);
    }

    @Override public long cacheTtlMillis() {
        return definition == null ? 0L : definition.getCacheTtlMillis();
    }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        String livePage = config("pageUrl", LIVE_PAGE);
        String pageReferer = config("pageReferer", "https://www.tvn.cl/");
        ProviderStreamParsers.TvnConfig providerConfig = ProviderStreamParsers.parseTvn(
                httpClient.getText(livePage, pageHeaders(pageReferer)),
                config("idPattern", ""),
                config("tokenPattern", ""),
                config("defaultStreamId", "57a498c4d7b86d600e5461cb")
        );
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", providerConfig.getAccessToken());
        String template = config("playlistTemplate", PLAYLIST_BASE + "{streamId}.m3u8");
        String playbackUrl = TokenHttpClient.buildUrl(
                template.replace("{streamId}", providerConfig.getStreamId()),
                query
        );
        URI playbackUri = URI.create(playbackUrl);
        Map<String, String> playbackHeaders = playbackHeaders(
                livePage,
                config("playbackOrigin", "https://live.tvn.cl")
        );
        validator.validate(playbackUri, playbackHeaders);
        return ResolvedPlaybackSource.dynamic(
                getId(),
                stableSourceId(channel),
                playbackUri,
                playbackHeaders,
                TokenHttpClient.BROWSER_USER_AGENT,
                expiresAt()
        );
    }

    private static Map<String, String> pageHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", referer);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "es-CL,es;q=0.9,en;q=0.8");
        return headers;
    }

    private static Map<String, String> playbackHeaders(String livePage, String origin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", livePage);
        headers.put("Origin", origin);
        headers.put("Cache-Control", "no-store, no-cache, max-age=0");
        headers.put("Pragma", "no-cache");
        return headers;
    }

    private String config(String key, String fallback) {
        return definition == null ? fallback : definition.getConfig(key, fallback);
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
    }
}
