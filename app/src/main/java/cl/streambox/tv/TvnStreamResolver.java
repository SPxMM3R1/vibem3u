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
    private static final long DEFAULT_TOKEN_CACHE_TTL_MILLIS = 5L * 60L * 1000L;
    private static final long DEFAULT_RESOLUTION_BUDGET_MILLIS = 12_000L;

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;
    private final TokenExpiryPolicy tokenExpiryPolicy;

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
        this.tokenExpiryPolicy = new TokenExpiryPolicy(DEFAULT_TOKEN_CACHE_TTL_MILLIS);
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
        if (definition == null) return DEFAULT_TOKEN_CACHE_TTL_MILLIS;
        if (!definition.getBooleanConfig("cacheEnabled", true)) return 0L;
        long configured = definition.getCacheTtlMillis();
        if (configured <= 0L) return DEFAULT_TOKEN_CACHE_TTL_MILLIS;
        // Token lifetime is provider-controlled. Keep the externally
        // configurable value bounded so a stale token cannot remain reusable
        // for an unexpectedly long period.
        return Math.min(configured, DEFAULT_TOKEN_CACHE_TTL_MILLIS);
    }

    @Override public boolean cacheResolvedSource() {
        return cacheTtlMillis() > 0L;
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
        ResolutionContext parent = ResolutionContext.current();
        long budget = resolutionBudgetMillis();
        ResolutionContext context = parent == null
                ? new ResolutionContext(budget)
                : parent.child(budget);
        try (ResolutionContext.Scope ignored = context.activate()) {
            context.check();
            return resolveInContext(channel, listener);
        }
    }

    private ResolvedPlaybackSource resolveInContext(
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
        long explicitExpiryAtMillis = ProviderStreamParsers.parseOptionalExpiryMillis(page);
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
                expiresAt(explicitExpiryAtMillis)
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
        return expiresAt(0L);
    }

    private long expiresAt(long explicitExpiryAtMillis) {
        long ttl = cacheTtlMillis();
        return ttl <= 0L
                ? 0L
                : tokenExpiryPolicy.effectiveExpiryAtMillis(
                        System.currentTimeMillis(),
                        explicitExpiryAtMillis > 0L
                                ? explicitExpiryAtMillis
                                : configuredExplicitExpiryAtMillis()
                );
    }

    private long resolutionBudgetMillis() {
        if (definition == null) return DEFAULT_RESOLUTION_BUDGET_MILLIS;
        return definition.getIntConfig(
                "resolutionBudgetMs",
                (int) DEFAULT_RESOLUTION_BUDGET_MILLIS,
                1_000,
                20_000
        );
    }

    /** Optional catalog metadata; zero means the provider supplied no expiry. */
    private long configuredExplicitExpiryAtMillis() {
        if (definition == null) return 0L;
        String value = definition.getConfig("tokenExpiresAtMillis", "");
        if (value.isBlank()) return 0L;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
