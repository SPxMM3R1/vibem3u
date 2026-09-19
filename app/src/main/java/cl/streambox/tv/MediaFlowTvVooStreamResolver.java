package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** TvVoo resolver variant that sends the original Vavoo destination to MediaFlow. */
public final class MediaFlowTvVooStreamResolver implements StreamResolver {
    private static final String PLAYBACK_USER_AGENT = "VAVOO/2.6";
    private static final int DEFAULT_BUDGET_MS = 12_000;
    private static final int MAX_DISCOVERY_STREAMS = 16;
    private static final int MAX_DISCOVERY_PAYLOAD_BYTES = 512 * 1024;

    private final ResolverDefinition definition;
    private final MediaFlowPreferences preferences;
    private final TokenHttpClient tvVooClient;
    private final MediaFlowExtractor extractor;

    public MediaFlowTvVooStreamResolver(
            ResolverDefinition definition,
            MediaFlowPreferences preferences
    ) {
        this(definition, preferences, new TokenHttpClient(), new MediaFlowExtractor());
    }

    MediaFlowTvVooStreamResolver(
            ResolverDefinition definition,
            MediaFlowPreferences preferences,
            TokenHttpClient tvVooClient,
            MediaFlowExtractor extractor
    ) {
        this.definition = definition;
        this.preferences = preferences;
        this.tvVooClient = tvVooClient;
        this.extractor = extractor;
    }

    @Override public String getId() { return definition.getId(); }

    @Override
    public boolean supports(Channel channel) {
        return definition.matchesExplicit(channel)
                || definition.matchesTvgId(channel)
                || definition.matchesHost(channel);
    }

    @Override public String stableSourceId(Channel channel) {
        return definition.stableSourceId(channel);
    }

    @Override public long cacheTtlMillis() {
        return definition.getCacheTtlMillis();
    }

    /** MediaFlow playback output is also session-only and may be reused until
     * the configured resolver TTL or an explicit playback rejection. */
    @Override public boolean cacheResolvedSource() { return true; }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        return resolve(channel, ResolutionProgressListener.NONE);
    }

    @Override
    public ResolvedPlaybackSource resolve(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        if (preferences == null || !preferences.isEnabled()) {
            throw new IOException("MediaFlow está desactivado.");
        }
        URI origin = preferences.getOriginUri();
        String password = preferences.getApiPassword();
        if (origin == null || AppStrings.isBlank(password)) {
            throw new IOException("Configura el origen y la contraseña API de MediaFlow.");
        }
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        ResolutionDeadline deadline = new ResolutionDeadline(definition.getIntConfig(
                "resolutionBudgetMs", DEFAULT_BUDGET_MS, 2_000, 20_000
        ));
        ResolutionContext current = ResolutionContext.current();
        if (current == null) {
            ResolutionContext root = new ResolutionContext(deadline.remainingMillis());
            try (ResolutionContext.Scope ignored = root.activate()) {
                return resolveInContext(channel, origin, password, progress, deadline);
            }
        }
        return resolveInContext(channel, origin, password, progress, deadline);
    }

    @Override
    public List<ResolvedPlaybackCandidate> resolvePlaybackCandidates(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolvedPlaybackSource source = resolve(channel, listener);
        return Collections.singletonList(new ResolvedPlaybackCandidate(
                "MediaFlow · Fuente 1",
                "MediaFlow · " + source.getMimeType(),
                source
        ));
    }

    private ResolvedPlaybackSource resolveInContext(
            Channel channel,
            URI origin,
            String password,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline
    ) throws IOException {
        if (channel == null) throw new IOException("Canal TvVoo ausente.");
        LinkedHashSet<String> aliases = new LinkedHashSet<>(definition.resolverAliases(channel));
        if (aliases.isEmpty()) throw new IOException("El canal no tiene alias TvVoo.");
        int maximum = definition.getIntConfig("maxAliases", 6, 1, 8);
        IOException lastError = null;
        int ordinal = 0;
        for (String alias : aliases) {
            if (ordinal++ >= maximum) break;
            try {
                deadline.check();
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.ALIAS_ATTEMPT,
                        "MediaFlow · consultando alias TvVoo"
                ));
                URI endpoint = MediaFlowTvVooEndpoint.forChannel(
                        definition, channel, origin, alias
                );
                TokenHttpClient.Response response = tvVooClient.getPublicOnHosts(
                        endpoint.toString(),
                        Collections.singletonMap("Accept", "application/json"),
                        512 * 1024,
                        null,
                        Collections.singleton("tvvoo.hayd.uk")
                );
                List<URI> originals = parseMediaFlowVavooCandidates(
                        new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)
                );
                for (URI original : originals) {
                    try {
                        deadline.check();
                        MediaFlowExtractor.Result extracted = extractor.extract(
                                origin, original, password
                        );
                        return ResolvedPlaybackSource.dynamic(
                                getId(),
                                stableSourceId(channel),
                                extracted.getPlaybackUri(),
                                extracted.getRequestHeaders(),
                                PLAYBACK_USER_AGENT,
                                expiresAt(),
                                alias,
                                extracted.getMimeType(),
                                origin
                        );
                    } catch (IOException error) {
                        lastError = error;
                    }
                }
                lastError = lastError == null
                        ? new IOException("TvVoo no publicó destinos Vavoo.")
                        : lastError;
            } catch (IOException error) {
                lastError = error;
            }
        }
        throw new IOException("MediaFlow no pudo resolver el canal TvVoo.", lastError);
    }

    /**
     * Parses only the TvVoo envelope used by MediaFlow discovery.
     *
     * <p>The generic TvVoo parser intentionally accepts HLS URLs and therefore
     * cannot be used here: MediaFlow discovery publishes extractor URLs whose
     * path is {@code /extractor/video} and whose {@code d} query parameter is
     * the original Vavoo destination. Every candidate is converted through
     * {@link MediaFlowVavooUrl}, which pins the discovery host/path and rejects
     * untrusted destinations before extraction receives them.</p>
     */
    static List<URI> parseMediaFlowVavooCandidates(String json) throws IOException {
        if (json == null || AppStrings.isBlank(json)) return Collections.emptyList();
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_DISCOVERY_PAYLOAD_BYTES) {
            throw new IOException("Respuesta TvVoo MediaFlow demasiado grande.");
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray streams = root.optJSONArray("streams");
            if (streams == null) return Collections.emptyList();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<URI> result = new ArrayList<>();
            for (int index = 0; index < streams.length()
                    && index < MAX_DISCOVERY_STREAMS; index++) {
                JSONObject stream = streams.optJSONObject(index);
                if (stream == null) continue;
                String value = stream.optString("url", "").trim();
                if (AppStrings.isBlank(value) || value.length() > 8192) continue;
                final URI proxy;
                try {
                    proxy = URI.create(value);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                try {
                    URI original = MediaFlowVavooUrl.fromTvVooProxy(
                            proxy,
                            MediaFlowTvVooEndpoint.discoveryOrigin()
                    );
                    if (seen.add(original.toString())) result.add(original);
                } catch (IOException ignored) {
                    // One malformed/untrusted stream must not hide later
                    // valid entries in the bounded discovery response.
                }
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException error) {
            throw new IOException("TvVoo devolvió JSON inválido.", error);
        }
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
    }
}
