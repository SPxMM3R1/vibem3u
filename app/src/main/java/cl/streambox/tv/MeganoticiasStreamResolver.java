package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves Meganoticias Ahora's short-lived MediaStream token. */
public final class MeganoticiasStreamResolver implements StreamResolver {
    public static final String ID = "meganoticias";
    private static final String TVG_ID = "MeganoticiasAhora.cl";
    private static final String LIVE_PAGE =
            "https://www.meganoticias.cl/senal-en-vivo/meganoticias/";
    private static final String API_URL = "https://api.mega.cl/api/v1/mdstrm";
    private static final String PLAYLIST_BASE = "https://mdstrm.com/live-stream-playlist/";

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;

    public MeganoticiasStreamResolver() {
        this(null, new TokenHttpClient());
    }

    public MeganoticiasStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient());
    }

    MeganoticiasStreamResolver(TokenHttpClient httpClient) {
        this(null, httpClient);
    }

    private MeganoticiasStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient
    ) {
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
        progress.onProgress(ResolutionProgress.of(ResolutionStage.PAGE_REQUEST));
        String page = httpClient.getText(livePage, pageHeaders(livePage));
        ProviderStreamParsers.MeganoticiasConfig providerConfig =
                ProviderStreamParsers.parseMeganoticiasConfig(
                        page,
                        config("idPattern", ""),
                        config("serverKeyPattern", "")
                );
        progress.onProgress(ResolutionProgress.of(ResolutionStage.PAGE_PARSED));

        Map<String, String> query = new LinkedHashMap<>();
        query.put("id", providerConfig.getStreamId());
        query.put("ua", TokenHttpClient.BROWSER_USER_AGENT);
        query.put("type", "live");
        query.put("process", "access_token");
        query.put("key", providerConfig.getServerKey());
        progress.onProgress(ResolutionProgress.of(ResolutionStage.TOKEN_REQUEST));
        String tokenJson = httpClient.getText(
                TokenHttpClient.buildUrl(config("apiUrl", API_URL), query),
                apiHeaders(livePage, config("playbackOrigin", "https://www.meganoticias.cl"))
        );
        String accessToken = ProviderStreamParsers.parseMeganoticiasAccessToken(
                tokenJson,
                config("accessTokenPath", "access_token")
        );

        Map<String, String> playbackQuery = new LinkedHashMap<>();
        playbackQuery.put("access_token", accessToken);
        String template = config("playlistTemplate", PLAYLIST_BASE + "{streamId}.m3u8");
        progress.onProgress(ResolutionProgress.of(ResolutionStage.SOURCE_BUILDING));
        String playbackUrl = TokenHttpClient.buildUrl(
                template.replace("{streamId}", providerConfig.getStreamId()),
                playbackQuery
        );
        URI playbackUri = URI.create(playbackUrl);
        Map<String, String> playbackHeaders = playbackHeaders(
                livePage,
                config("playbackOrigin", "https://www.meganoticias.cl")
        );
        validator.validate(playbackUri, playbackHeaders, progress);
        progress.onProgress(ResolutionProgress.of(ResolutionStage.SOURCE_FOUND));
        return ResolvedPlaybackSource.dynamic(
                getId(),
                stableSourceId(channel),
                playbackUri,
                playbackHeaders,
                TokenHttpClient.BROWSER_USER_AGENT,
                expiresAt()
        );
    }

    private static Map<String, String> pageHeaders(String livePage) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", livePage);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "es-CL,es;q=0.9,en;q=0.8");
        return headers;
    }

    private static Map<String, String> apiHeaders(String livePage, String origin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", livePage);
        headers.put("Origin", origin);
        headers.put("Accept", "application/json");
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
