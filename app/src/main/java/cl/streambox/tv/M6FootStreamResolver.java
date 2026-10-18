package cl.streambox.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the tokenless M6Foot references at open time.
 *
 * <p>The published M3U only carries {@code vibem3u://resolver/m6foot/<id>}.
 * The player page, the embed configuration and the signed HLS URL are fetched
 * in RAM; the CDN signature and the required Referer are never persisted.</p>
 */
public final class M6FootStreamResolver implements StreamResolver {
    public static final String ID = "m6foot";
    static final String PAGE_BASE = "https://m6foot.click/player/3/";
    static final String EMBED_BASE = "https://navytitle.net/embed/";
    static final String EMBED_REFERER = "https://navytitle.net/";
    private static final long CACHE_TTL_MS = 300_000L;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EMBED_BYTES = 2 * 1024 * 1024;
    private static final Pattern PLAYER_ID = Pattern.compile("[0-9]{1,6}");
    private static final Pattern EMBED = Pattern.compile(
            "navytitle\\.net/embed/([A-Za-z0-9_-]{4,64})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ECONFIG = Pattern.compile(
            "_econfig\\s*=\\s*['\"]([^'\"]+)['\"]"
    );
    private static final int[] SLOTS = {2, 0, 3, 1};

    private final TokenHttpClient http;
    private final HlsStreamValidator validator;
    private final String pageBase;
    private final String embedBase;
    private final String embedReferer;

    public M6FootStreamResolver() {
        this(new TokenHttpClient());
    }

    M6FootStreamResolver(TokenHttpClient http) {
        this(http, PAGE_BASE, EMBED_BASE, EMBED_REFERER);
    }

    public M6FootStreamResolver(ResolverDefinition definition) {
        this(
                new TokenHttpClient(),
                definition == null ? PAGE_BASE : definition.getConfig("pageBase", PAGE_BASE),
                definition == null ? EMBED_BASE : definition.getConfig("embedBase", EMBED_BASE),
                definition == null ? EMBED_REFERER : definition.getConfig("embedReferer", EMBED_REFERER)
        );
    }

    M6FootStreamResolver(TokenHttpClient http, String pageBase, String embedBase, String embedReferer) {
        this.http = http;
        this.validator = new HlsStreamValidator(http);
        this.pageBase = withTrailingSlash(pageBase, PAGE_BASE);
        this.embedBase = withTrailingSlash(embedBase, EMBED_BASE);
        this.embedReferer = AppStrings.isBlank(embedReferer) ? EMBED_REFERER : embedReferer.trim();
    }

    @Override public String getId() { return ID; }

    @Override public boolean supports(Channel channel) {
        return channel != null
                && ID.equalsIgnoreCase(DynamicSourceReference.provider(channel.getStreamUri()));
    }

    @Override public long cacheTtlMillis() { return CACHE_TTL_MS; }

    @Override public boolean keepSessionSourceOnPlaybackPause() { return true; }

    @Override public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        return resolve(channel, ResolutionProgressListener.NONE);
    }

    @Override public ResolvedPlaybackSource resolve(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        if (!supports(channel)) throw new IOException("Referencia M6Foot no válida.");
        String playerId = DynamicSourceReference.stableId(channel.getStreamUri());
        if (!PLAYER_ID.matcher(playerId).matches()) {
            throw new IOException("Referencia M6Foot no válida.");
        }

        ResolutionContext parent = ResolutionContext.current();
        ResolutionContext context = parent == null
                ? new ResolutionContext(20_000L)
                : parent.child(20_000L);
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        try (ResolutionContext.Scope ignored = context.activate()) {
            context.check();
            String page = fetchText(
                    pageBase + playerId,
                    Set.of("m6foot.click", "www.m6foot.click"),
                    MAX_PAGE_BYTES
            );
            Matcher embed = EMBED.matcher(page);
            if (!embed.find()) throw new IOException("M6Foot no publicó el reproductor.");

            context.check();
            String document = fetchText(
                    embedBase + embed.group(1),
                    Set.of("navytitle.net", "www.navytitle.net"),
                    MAX_EMBED_BYTES
            );
            Matcher econfig = ECONFIG.matcher(document);
            if (!econfig.find()) {
                throw new IOException("M6Foot no publicó la configuración del reproductor.");
            }
            JSONObject payload = decodeConfig(econfig.group(1));
            String streamUrl = payload.optString("stream_url", "").trim();
            if (AppStrings.isBlank(streamUrl)) {
                streamUrl = payload.optString("stream_url_nop2p", "").trim();
            }
            if (AppStrings.isBlank(streamUrl)) {
                throw new IOException("M6Foot no entregó una señal válida.");
            }

            URI uri;
            try {
                uri = URI.create(streamUrl);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("M6Foot entregó una señal no válida.");
            }
            PublicStreamPolicy.requirePublicHttp(uri);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", TokenHttpClient.BROWSER_USER_AGENT);
            headers.put("Referer", embedReferer);
            context.check();
            validator.validate(uri, headers);

            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.SOURCE_FOUND,
                    "M6Foot · HLS y segmento validados · Referer solo en RAM"
            ));
            return ResolvedPlaybackSource.dynamic(
                    ID,
                    stableSourceId(channel),
                    uri,
                    headers,
                    headers.get("User-Agent"),
                    System.currentTimeMillis() + CACHE_TTL_MS,
                    null,
                    "application/x-mpegURL",
                    null
            );
        } catch (IOException error) {
            if (context.isCancelled()) {
                throw new IOException("Resolución M6Foot cancelada.");
            }
            String message = error.getMessage();
            if (message != null && message.contains("M6Foot")) throw error;
            throw new IOException("M6Foot no entregó una fuente HLS válida en este momento.");
        }
    }

    private String fetchText(String url, Set<String> hosts, int maxBytes) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", TokenHttpClient.BROWSER_USER_AGENT);
        headers.put("Accept", "text/html,application/xhtml+xml");
        TokenHttpClient.Response response = http.getPublicOnHosts(
                url,
                headers,
                maxBytes,
                null,
                hosts
        );
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw new IOException("M6Foot no respondió.");
        }
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    /**
     * Decodes the embed's {@code _econfig}: base64, four interleaved quarters,
     * drop the fourth character of each quarter, reorder {@code [2,0,3,1]},
     * join and base64-decode again into JSON.
     */
    static JSONObject decodeConfig(String encoded) throws IOException {
        if (AppStrings.isBlank(encoded) || encoded.length() > 4_000_000) {
            throw new IOException("Configuración M6Foot inválida.");
        }
        try {
            String inner = new String(
                    Base64.getDecoder().decode(encoded),
                    StandardCharsets.UTF_8
            );
            if (inner.length() < 32 || inner.length() % 4 != 0) {
                throw new IOException("Configuración M6Foot inválida.");
            }
            int quarter = inner.length() / 4;
            String[] parts = new String[4];
            for (int index = 0; index < 4; index++) {
                String part = inner.substring(index * quarter, (index + 1) * quarter);
                if (part.length() < 5) {
                    throw new IOException("Configuración M6Foot inválida.");
                }
                String adjusted = part.substring(0, 3) + part.substring(4);
                parts[SLOTS[index]] = new String(
                        Base64.getDecoder().decode(adjusted),
                        StandardCharsets.UTF_8
                );
            }
            String joined = parts[0] + parts[1] + parts[2] + parts[3];
            return new JSONObject(new String(
                    Base64.getDecoder().decode(joined),
                    StandardCharsets.UTF_8
            ));
        } catch (IllegalArgumentException | JSONException error) {
            throw new IOException("Configuración M6Foot inválida.", error);
        }
    }

    private static String withTrailingSlash(String value, String fallback) {
        String candidate = AppStrings.isBlank(value) ? fallback : value.trim();
        return candidate.endsWith("/") ? candidate : candidate + "/";
    }
}
