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

    @Override public boolean cacheResolvedSource() {
        return false;
    }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        return resolve(channel, ResolutionProgressListener.NONE);
    }

    @Override
    public ResolvedPlaybackSource resolve(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        String livePage = config("pageUrl", LIVE_PAGE);
        String pageReferer = config("pageReferer", "https://www.tvn.cl/");
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.PAGE_REQUEST,
                "GET " + SafePlaybackText.url(livePage)
                        + " · HTML · configuración pública"
        ));
        String page = httpClient.getText(livePage, pageHeaders(pageReferer));
        ProviderStreamParsers.TvnConfig providerConfig = ProviderStreamParsers.parseTvn(
                page,
                config("idPattern", ""),
                config("tokenPattern", ""),
                config("defaultStreamId", "57a498c4d7b86d600e5461cb")
        );
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.PAGE_PARSED,
                "HTML válido · buscando id=" + providerConfig.getStreamId()
                        + " + access_token=[oculto]"
        ));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", providerConfig.getAccessToken());
        String template = config("playlistTemplate", PLAYLIST_BASE + "{streamId}.m3u8");
        String playbackUrl = TokenHttpClient.buildUrl(
                template.replace("{streamId}", providerConfig.getStreamId()),
                query
        );
        URI playbackUri = URI.create(playbackUrl);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.SOURCE_BUILDING,
                "GET " + SafePlaybackText.url(playbackUri)
                        + " · token solo en memoria"
        ));
        String playbackOrigin = config("playbackOrigin", "https://live.tvn.cl");
        Map<String, String> playbackHeaders = playbackHeaders(
                livePage,
                playbackOrigin
        );
        validator.validateForPlayback(playbackUri, playbackHeaders, progress);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.SOURCE_FOUND,
                "Playlist HLS válida · id=" + providerConfig.getStreamId()
                        + " · Referer=" + SafePlaybackText.url(livePage)
                        + " · Origin=" + SafePlaybackText.url(playbackOrigin)
                        + " · Media3 validará variante/segmento"
        ));
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
