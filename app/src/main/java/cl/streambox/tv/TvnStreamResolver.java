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

    private final TokenHttpClient httpClient;

    public TvnStreamResolver() {
        this(new TokenHttpClient());
    }

    TvnStreamResolver(TokenHttpClient httpClient) {
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
        ProviderStreamParsers.TvnConfig config = ProviderStreamParsers.parseTvn(
                httpClient.getText(LIVE_PAGE, pageHeaders())
        );
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", config.getAccessToken());
        String playbackUrl = TokenHttpClient.buildUrl(
                PLAYLIST_BASE + config.getStreamId() + ".m3u8",
                query
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
        headers.put("Referer", "https://www.tvn.cl/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "es-CL,es;q=0.9,en;q=0.8");
        return headers;
    }

    private static Map<String, String> playbackHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", LIVE_PAGE);
        headers.put("Origin", "https://live.tvn.cl");
        headers.put("Cache-Control", "no-store, no-cache, max-age=0");
        headers.put("Pragma", "no-cache");
        return headers;
    }
}
