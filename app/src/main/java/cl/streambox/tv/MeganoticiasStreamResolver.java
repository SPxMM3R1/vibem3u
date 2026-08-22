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

    private final TokenHttpClient httpClient;

    public MeganoticiasStreamResolver() {
        this(new TokenHttpClient());
    }

    MeganoticiasStreamResolver(TokenHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public boolean supports(Channel channel) {
        return channel != null && TVG_ID.equalsIgnoreCase(channel.getTvgId().trim());
    }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        ProviderStreamParsers.MeganoticiasConfig config =
                ProviderStreamParsers.parseMeganoticiasConfig(
                        httpClient.getText(LIVE_PAGE, pageHeaders())
                );

        Map<String, String> query = new LinkedHashMap<>();
        query.put("id", config.getStreamId());
        query.put("ua", TokenHttpClient.BROWSER_USER_AGENT);
        query.put("type", "live");
        query.put("process", "access_token");
        query.put("key", config.getServerKey());
        String tokenJson = httpClient.getText(
                TokenHttpClient.buildUrl(API_URL, query),
                apiHeaders()
        );
        String accessToken = ProviderStreamParsers.parseMeganoticiasAccessToken(tokenJson);

        Map<String, String> playbackQuery = new LinkedHashMap<>();
        playbackQuery.put("access_token", accessToken);
        String playbackUrl = TokenHttpClient.buildUrl(
                PLAYLIST_BASE + config.getStreamId() + ".m3u8",
                playbackQuery
        );
        return ResolvedPlaybackSource.dynamic(
                ID,
                URI.create(playbackUrl),
                playbackHeaders(),
                TokenHttpClient.BROWSER_USER_AGENT
        );
    }

    private static Map<String, String> pageHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", LIVE_PAGE);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "es-CL,es;q=0.9,en;q=0.8");
        return headers;
    }

    private static Map<String, String> apiHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", LIVE_PAGE);
        headers.put("Origin", "https://www.meganoticias.cl");
        headers.put("Accept", "application/json");
        return headers;
    }

    private static Map<String, String> playbackHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", LIVE_PAGE);
        headers.put("Origin", "https://www.meganoticias.cl");
        headers.put("Cache-Control", "no-store, no-cache, max-age=0");
        headers.put("Pragma", "no-cache");
        return headers;
    }
}
