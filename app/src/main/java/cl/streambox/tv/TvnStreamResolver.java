package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Resolves TVN's short-lived MediaStream token from its public live page. */
public final class TvnStreamResolver implements StreamResolver {
    public static final String ID = "tvn";
    private static final String TVG_ID = "0104";
    private static final String LIVE_PAGE =
            "https://tvn-live-test-506364290967.southamerica-west1.run.app";
    /** tvn.cl publica aquí la dirección vigente del reproductor en vivo. */
    private static final String DISCOVERY_PAGE = "https://www.tvn.cl/en-vivo";
    private static final String LEGACY_LIVE_PAGE = "https://live.tvn.cl/?tvn_seccion=prehome";
    /** Cada dirección tiene su propio plazo: una que no responde no agota el total. */
    private static final long PAGE_ATTEMPT_BUDGET_MILLIS = 4_000L;
    private static final String PLAYLIST_BASE = "https://mdstrm.com/live-stream-playlist/";
    private static final long SESSION_TOKEN_CACHE_TTL_MILLIS = Long.MAX_VALUE;
    /**
     * Página del reproductor ya descubierta en tvn.cl (0.5.85). Se recuerda 6 h para no bajar
     * tvn.cl/en-vivo en cada token nuevo; si deja de publicar el token, se vuelve a descubrir.
     */
    private static final long LIVE_PAGE_MEMORY_MILLIS = 6L * 60L * 60L * 1000L;
    private static volatile String rememberedLivePage;
    private static volatile long rememberedLivePageAtMillis;
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
        this.tokenExpiryPolicy = new TokenExpiryPolicy(cacheTtlFor(definition));
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
        return cacheTtlFor(definition);
    }

    @Override public boolean cacheResolvedSource() {
        return cacheTtlMillis() > 0L;
    }

    @Override public boolean keepSessionSourceOnPlaybackPause() {
        return cacheResolvedSource();
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
        String pageReferer = config("pageReferer", "https://www.tvn.cl/");
        // TVN movió su reproductor (live.tvn.cl dejó de responder en 2026-09):
        // primero se lee la dirección vigente desde tvn.cl y luego se prueban
        // las conocidas, cada una con su propio plazo.
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String remembered = rememberedLivePage();
        String discovered = remembered != null ? remembered : discoverLivePage(pageReferer, progress);
        if (discovered != null) candidates.add(discovered);
        candidates.add(config("pageUrl", LIVE_PAGE));
        for (String fallback : config("fallbackPageUrls", LEGACY_LIVE_PAGE).split(",")) {
            if (!AppStrings.isBlank(fallback)) candidates.add(fallback.trim());
        }
        String livePage = null;
        String page = null;
        IOException lastError = null;
        for (String candidate : candidates) {
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.PAGE_REQUEST,
                    "GET " + SafePlaybackText.url(candidate)
                            + " · HTML · configuración pública"
            ));
            try {
                page = getWithinAttemptBudget(candidate, pageHeaders(pageReferer));
                if (page.contains("access_token")) {
                    livePage = candidate;
                    rememberedLivePage = candidate;
                    rememberedLivePageAtMillis = System.currentTimeMillis();
                    break;
                }
                lastError = new IOException("TVN no publicó el reproductor en esa dirección.");
            } catch (IOException error) {
                lastError = error;
            }
            ResolutionContext.current().check();
        }
        if (livePage == null) {
            rememberedLivePage = null;
            throw lastError != null ? lastError
                    : new IOException("TVN no publicó el reproductor en vivo.");
        }
        ProviderStreamParsers.TvnConfig providerConfig = ProviderStreamParsers.parseTvn(
                page,
                config("idPattern", ""),
                config("tokenPattern", ""),
                config("defaultStreamId", "57a498c4d7b86d600e5461cb")
        );
        // Restrict page metadata to the object that published this exact
        // token; ad/player expiration fields elsewhere on the page are not
        // evidence that the TVN stream token is expiring.
        long explicitExpiryAtMillis = ProviderStreamParsers.parseTvnExpiryMillis(
                page,
                providerConfig.getAccessToken()
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

    private static String rememberedLivePage() {
        String page = rememberedLivePage;
        if (page == null) return null;
        long age = System.currentTimeMillis() - rememberedLivePageAtMillis;
        return age >= 0L && age < LIVE_PAGE_MEMORY_MILLIS ? page : null;
    }

    /** Lee en tvn.cl la dirección vigente del reproductor; null si no se pudo. */
    private String discoverLivePage(String referer, ResolutionProgressListener progress) {
        String discoveryPage = config("discoveryUrl", DISCOVERY_PAGE);
        if (AppStrings.isBlank(discoveryPage)) return null;
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.PAGE_REQUEST,
                "GET " + SafePlaybackText.url(discoveryPage) + " · dirección del reproductor"
        ));
        try {
            return ProviderStreamParsers.parseTvnLivePageUrl(
                    getWithinAttemptBudget(discoveryPage, pageHeaders(referer)));
        } catch (IOException error) {
            return null;
        }
    }

    private String getWithinAttemptBudget(String url, Map<String, String> headers) throws IOException {
        ResolutionContext parent = ResolutionContext.current();
        ResolutionContext attempt = parent == null
                ? new ResolutionContext(PAGE_ATTEMPT_BUDGET_MILLIS)
                : parent.child(PAGE_ATTEMPT_BUDGET_MILLIS);
        try (ResolutionContext.Scope ignored = attempt.activate()) {
            return httpClient.getText(url, headers);
        }
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

    private static long cacheTtlFor(ResolverDefinition definition) {
        if (definition == null) return SESSION_TOKEN_CACHE_TTL_MILLIS;
        if (!definition.getBooleanConfig("cacheEnabled", true)) return 0L;
        long configured = definition.getCacheTtlMillis();
        return configured <= 0L
                ? SESSION_TOKEN_CACHE_TTL_MILLIS
                : Math.min(configured, SESSION_TOKEN_CACHE_TTL_MILLIS);
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
        if (AppStrings.isBlank(value)) return 0L;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
