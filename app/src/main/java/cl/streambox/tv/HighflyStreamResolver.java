package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves a short-lived Highfly stream immediately before playback. */
public final class HighflyStreamResolver implements StreamResolver {
    static final String DEFAULT_STREAM_API_TEMPLATE =
            "https://sports.highfly.to/stream/sport/leaf:{slug}.json";
    private static final String DEFAULT_STREAM_ARRAY_PATH = "streams";
    private static final int DEFAULT_MAX_STREAMS = 16;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final int DEFAULT_PARALLEL_CANDIDATES = 2;
    private static final long DEFAULT_RESOLUTION_BUDGET_MILLIS = 12_000L;
    private static final String PLAYBACK_USER_AGENT = TokenHttpClient.BROWSER_USER_AGENT;
    private static final Set<String> STREAM_API_HOSTS = Collections.singleton(
            "sports.highfly.to"
    );
    private static final Set<String> STREAM_HOSTS = streamHosts();

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsCandidateValidator validator;

    public HighflyStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient(), new HlsStreamValidator());
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this(
                definition,
                httpClient,
                (uri, headers, listener) -> validator.validate(uri, headers, listener)
        );
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsCandidateValidator validator
    ) {
        if (definition == null) throw new NullPointerException("definition");
        if (httpClient == null) throw new NullPointerException("httpClient");
        if (validator == null) throw new NullPointerException("validator");
        this.definition = definition;
        this.httpClient = httpClient;
        this.validator = validator;
    }

    @Override public String getId() { return definition.getId(); }

    @Override
    public boolean supports(Channel channel) {
        return definition.matchesExplicit(channel)
                || definition.matchesTvgId(channel)
                || definition.matchesHost(channel);
    }

    @Override
    public String stableSourceId(Channel channel) {
        String slug = stableSlug(channel);
        return AppStrings.isBlank(slug) ? definition.stableSourceId(channel) : slug;
    }

    @Override public long cacheTtlMillis() { return definition.getCacheTtlMillis(); }

    /** Highfly URLs are signed/short-lived and are never reused by the coordinator. */
    @Override public boolean cacheResolvedSource() { return false; }

    /**
     * Resolves every currently published Highfly stream for the explicit
     * source selector. Normal playback still uses {@link #resolve} and stops
     * at the first valid candidate, so opening a channel does not wait for all
     * alternatives.
     */
    @Override
    public List<ResolvedPlaybackCandidate> resolvePlaybackCandidates(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        long budget = resolutionBudgetMillis();
        ResolutionContext parent = ResolutionContext.current();
        ResolutionContext context = parent == null
                ? new ResolutionContext(budget)
                : parent.child(budget);
        try (ResolutionContext.Scope ignored = context.activate()) {
            context.check();
            return resolvePlaybackCandidatesInContext(channel, progress, budget);
        }
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
        String slug = stableSlug(channel);
        if (AppStrings.isBlank(slug)) {
            return fallbackSource(channel, progress, null);
        }

        URI apiUri;
        try {
            apiUri = streamApiUri(slug);
        } catch (IOException error) {
            return fallbackSource(channel, progress, error);
        }

        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.PAGE_REQUEST,
                "GET " + SafePlaybackText.url(apiUri) + " · JSON de streams Highfly"
        ));

        String payload;
        try {
            TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                    apiUri.toString(),
                    highflyHeaders("application/json"),
                    maximumPayloadBytes(),
                    null,
                    STREAM_API_HOSTS
            );
            payload = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.PAGE_PARSED,
                    "HTTP " + response.getStatusCode() + " · JSON de streams recibido"
            ));
        } catch (IOException error) {
            return fallbackSource(channel, progress, error);
        }

        List<ResolverPayloadParsers.HighflyCandidate> candidates;
        try {
            candidates = ResolverPayloadParsers.parseHighflyCandidates(
                    payload,
                    streamArrayPath(),
                    maximumStreams()
            );
        } catch (IOException error) {
            return fallbackSource(channel, progress, error);
        }
        if (candidates.isEmpty()) return fallbackSource(channel, progress, null);

        IOException lastError = null;
        for (int index = 0; index < candidates.size(); index++) {
            ResolverPayloadParsers.HighflyCandidate candidate = candidates.get(index);
            progress.onProgress(ResolutionProgress.counted(
                    ResolutionStage.SOURCE_CANDIDATE,
                    index + 1,
                    candidates.size(),
                    candidateDetail(candidate)
            ));
            try {
                URI playbackUri = validateCandidateUri(candidate.getUri());
                // Strict validation deliberately walks the HLS playlist, a
                // selected child playlist and a recent media segment before
                // handing the signed URL to Media3. A failed candidate does
                // not enter the handoff cache, so the next one is independent.
                validator.validate(playbackUri, highflyHeaders("*/*"), progress);
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.SOURCE_FOUND,
                        "HLS Highfly válido · candidato " + (index + 1)
                                + " · " + candidateDetail(candidate)
                ));
                return ResolvedPlaybackSource.dynamic(
                        getId(),
                        stableSourceId(channel),
                        playbackUri,
                        highflyHeaders("*/*"),
                        PLAYBACK_USER_AGENT,
                        expiresAt()
                );
            } catch (IOException error) {
                lastError = error;
            }
        }

        return fallbackSource(channel, progress, lastError);
    }

    private List<ResolvedPlaybackCandidate> resolvePlaybackCandidatesInContext(
            Channel channel,
            ResolutionProgressListener progress,
            long budgetMillis
    ) throws IOException {
        List<ResolverPayloadParsers.HighflyCandidate> candidates;
        try {
            candidates = fetchCandidates(channel, progress);
        } catch (IOException error) {
            return fallbackCandidate(channel, progress, error);
        }
        if (candidates.isEmpty()) return fallbackCandidate(channel, progress, null);

        ResolutionDeadline deadline = new ResolutionDeadline(budgetMillis);
        LinkedHashSet<URI> acceptedUris = new LinkedHashSet<>();
        IOException lastError = null;
        HlsCandidateRace.Streaming candidateRace = new HlsCandidateRace.Streaming(
                candidates.size(),
                parallelCandidates(),
                deadline,
                0,
                candidates.size(),
                progress,
                candidate -> {
                    URI playbackUri = validateCandidateUri(candidate);
                    validator.validate(playbackUri, highflyHeaders("*/*"), progress);
                    return playbackUri;
                }
        );
        try {
            for (ResolverPayloadParsers.HighflyCandidate candidate : candidates) {
                candidateRace.submit(candidate.getUri());
            }
            while (candidateRace.hasInFlight()) {
                try {
                    deadline.check();
                } catch (IOException deadlineError) {
                    if (acceptedUris.isEmpty()) throw deadlineError;
                    lastError = deadlineError;
                    break;
                }
                HlsCandidateRace.Attempt attempt = candidateRace.poll(
                        Math.min(250L, Math.max(1L, deadline.remainingMillis()))
                );
                if (attempt == null) {
                    if (acceptedUris.isEmpty()) {
                        throw new IOException("Tiempo de resolución agotado.");
                    }
                    break;
                }
                if (attempt.getAccepted() != null) {
                    acceptedUris.add(attempt.getAccepted());
                } else if (attempt.getError() != null) {
                    lastError = attempt.getError();
                }
            }
        } finally {
            candidateRace.close();
        }

        List<ResolvedPlaybackCandidate> result = new java.util.ArrayList<>();
        for (ResolverPayloadParsers.HighflyCandidate candidate : candidates) {
            if (!acceptedUris.contains(candidate.getUri())) continue;
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.SOURCE_FOUND,
                    "HLS Highfly válido · " + candidateDetail(candidate)
            ));
            result.add(new ResolvedPlaybackCandidate(
                    candidate.displayLabel(),
                    highflyCandidateDetail(candidate),
                    ResolvedPlaybackSource.dynamic(
                            getId(),
                            stableSourceId(channel),
                            candidate.getUri(),
                            highflyHeaders("*/*"),
                            PLAYBACK_USER_AGENT,
                            expiresAt()
                    )
            ));
        }
        if (result.isEmpty()) return fallbackCandidate(channel, progress, lastError);
        return Collections.unmodifiableList(result);
    }

    private List<ResolverPayloadParsers.HighflyCandidate> fetchCandidates(
            Channel channel,
            ResolutionProgressListener progress
    ) throws IOException {
        String slug = stableSlug(channel);
        if (AppStrings.isBlank(slug)) return Collections.emptyList();
        URI apiUri = streamApiUri(slug);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.PAGE_REQUEST,
                "GET " + SafePlaybackText.url(apiUri) + " · JSON de streams Highfly"
        ));
        TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                apiUri.toString(),
                highflyHeaders("application/json"),
                maximumPayloadBytes(),
                null,
                STREAM_API_HOSTS
        );
        ResolutionContext active = ResolutionContext.current();
        if (active != null) active.check();
        String payload = new String(
                response.getBody(),
                java.nio.charset.StandardCharsets.UTF_8
        );
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.PAGE_PARSED,
                "HTTP " + response.getStatusCode() + " · JSON de streams recibido"
        ));
        return ResolverPayloadParsers.parseHighflyCandidates(
                payload,
                streamArrayPath(),
                maximumStreams()
        );
    }

    private List<ResolvedPlaybackCandidate> fallbackCandidate(
            Channel channel,
            ResolutionProgressListener progress,
            IOException cause
    ) throws IOException {
        ResolvedPlaybackSource source = fallbackSource(channel, progress, cause);
        return Collections.singletonList(new ResolvedPlaybackCandidate(
                "Fuente actual",
                "Highfly · URL HLS de respaldo",
                source
        ));
    }

    private ResolvedPlaybackSource fallbackSource(
            Channel channel,
            ResolutionProgressListener progress,
            IOException cause
    ) throws IOException {
        if (channel == null || channel.getStreamUri() == null) {
            throw cause == null
                    ? new IOException("Highfly no tiene una URL HLS de respaldo.")
                    : new IOException("Highfly no entregó una fuente reproducible.", cause);
        }
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.SOURCE_BUILDING,
                "Highfly usa la URL HLS de respaldo de la M3U"
        ));
        return ResolvedPlaybackSource.fallback(channel, getId(), PLAYBACK_USER_AGENT);
    }

    private URI streamApiUri(String slug) throws IOException {
        if (!slug.matches("[A-Za-z0-9_-]{2,128}")) {
            throw new IOException("Slug Highfly inválido.");
        }
        String template = definition.getConfig(
                "streamApiTemplate",
                DEFAULT_STREAM_API_TEMPLATE
        ).trim();
        // The endpoint is intentionally exact. A catalogue entry cannot turn
        // the M3U slug into an arbitrary URL or redirect the API request to a
        // host outside the Highfly allowlist.
        if (!DEFAULT_STREAM_API_TEMPLATE.equals(template)) {
            throw new IOException("Plantilla de streams Highfly no permitida.");
        }
        try {
            URI uri = URI.create(template.replace("{slug}", slug));
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"sports.highfly.to".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !uri.getRawPath().equals("/stream/sport/leaf:" + slug + ".json")) {
                throw new IOException("Endpoint de streams Highfly no permitido.");
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IOException("Endpoint de streams Highfly inválido.", error);
        }
    }

    private URI validateCandidateUri(URI candidate) throws IOException {
        if (candidate == null || candidate.getHost() == null
                || candidate.getUserInfo() != null
                || candidate.getPort() != -1
                || !"https".equalsIgnoreCase(candidate.getScheme())) {
            throw new IOException("Highfly publicó una URL HLS no permitida.");
        }
        String host = candidate.getHost().toLowerCase(Locale.ROOT);
        if (!STREAM_HOSTS.contains(host)) {
            throw new IOException("Highfly publicó un host HLS no permitido.");
        }
        String path = candidate.getPath();
        if (path == null || !path.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            throw new IOException("Highfly publicó una URL que no es HLS.");
        }
        return candidate;
    }

    private static String stableSlug(Channel channel) {
        if (channel == null || channel.getAttributes() == null) return "";
        String configured = channel.getAttributes().get("x-resolver-id");
        return configured != null && configured.matches("[A-Za-z0-9_-]{2,128}")
                ? configured
                : "";
    }

    private static String candidateDetail(ResolverPayloadParsers.HighflyCandidate candidate) {
        String label = candidate == null ? "Fuente Highfly" : candidate.displayLabel();
        String detail = candidate == null ? "" : candidate.displayDetail();
        if (AppStrings.isBlank(detail)) return label;
        return label + " · " + detail;
    }

    private static String highflyCandidateDetail(
            ResolverPayloadParsers.HighflyCandidate candidate
    ) {
        String detail = candidate == null ? "" : candidate.displayDetail();
        return AppStrings.isBlank(detail)
                ? "Highfly · HLS validada"
                : "Highfly · " + detail + " · HLS validada";
    }

    private static Map<String, String> highflyHeaders(String accept) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", PLAYBACK_USER_AGENT);
        headers.put("Referer", "https://sports.highfly.to/");
        headers.put("Origin", "https://sports.highfly.to");
        if (!AppStrings.isBlank(accept)) headers.put("Accept", accept);
        return Collections.unmodifiableMap(headers);
    }

    private String streamArrayPath() throws IOException {
        String path = definition.getConfig("streamArrayPath", DEFAULT_STREAM_ARRAY_PATH).trim();
        if (!path.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){0,7}")) {
            throw new IOException("Ruta de streams Highfly no permitida.");
        }
        return path;
    }

    private int maximumStreams() {
        return definition.getIntConfig("maxStreams", DEFAULT_MAX_STREAMS, 1, 32);
    }

    private int maximumPayloadBytes() {
        return definition.getIntConfig(
                "maxPayloadBytes",
                DEFAULT_MAX_PAYLOAD_BYTES,
                16 * 1024,
                512 * 1024
        );
    }

    private int parallelCandidates() {
        return definition.getIntConfig(
                "parallelCandidates",
                DEFAULT_PARALLEL_CANDIDATES,
                1,
                4
        );
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
    }

    private long resolutionBudgetMillis() {
        return definition.getIntConfig(
                "resolutionBudgetMs",
                (int) DEFAULT_RESOLUTION_BUDGET_MILLIS,
                1_000,
                20_000
        );
    }

    private static Set<String> streamHosts() {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add("leaf.highfly.dev");
        hosts.add("papacito.cfd");
        return Collections.unmodifiableSet(hosts);
    }

    @FunctionalInterface
    interface HlsCandidateValidator {
        void validate(
                URI playbackUri,
                Map<String, String> headers,
                ResolutionProgressListener listener
        ) throws IOException;
    }
}
