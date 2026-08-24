package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves the active MediaStream ID from the official 24 Horas page. */
public final class TwentyFourHoursStreamResolver implements StreamResolver {
    private static final String DEFAULT_PAGE = "https://www.24horas.cl/envivo";
    private static final String DEFAULT_TEMPLATE =
            "https://mdstrm.com/live-stream-playlist/{streamId}.m3u8";
    private static final String DEFAULT_STREAM_ID = "57d1a22064f5d85712b20dab";

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;

    public TwentyFourHoursStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient(), new HlsStreamValidator());
    }

    TwentyFourHoursStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.validator = validator;
    }

    @Override public String getId() { return definition.getId(); }

    @Override
    public boolean supports(Channel channel) {
        return definition.matchesExplicit(channel) || definition.matchesTvgId(channel);
    }

    @Override
    public String stableSourceId(Channel channel) {
        return definition.stableSourceId(channel);
    }

    @Override
    public long cacheTtlMillis() {
        return definition.getCacheTtlMillis();
    }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        String pageUrl = definition.getConfig("pageUrl", DEFAULT_PAGE);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", pageUrl);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "es-CL,es;q=0.9,en;q=0.8");
        String streamId = ResolverPayloadParsers.parseTwentyFourHoursStreamId(
                httpClient.getText(pageUrl, headers),
                definition.getConfig("streamIdPattern", ""),
                definition.getConfig("defaultStreamId", DEFAULT_STREAM_ID)
        );
        String playback = definition.getConfig("playlistTemplate", DEFAULT_TEMPLATE)
                .replace("{streamId}", streamId);
        URI playbackUri = URI.create(playback);
        validator.validate(playbackUri, Collections.emptyMap());
        return ResolvedPlaybackSource.dynamic(
                getId(),
                stableSourceId(channel),
                playbackUri,
                Collections.emptyMap(),
                TokenHttpClient.BROWSER_USER_AGENT,
                expiresAt()
        );
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
    }
}
