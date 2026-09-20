package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves a short-lived Highfly stream immediately before playback. */
public final class HighflyStreamResolver implements StreamResolver {
    static final String DEFAULT_MANIFEST_URL =
            "https://sports.highfly.to/manifest.json";
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
    private final String configuredManifestUrl;
    private final TokenHttpClient httpClient;
    private final HlsCandidateValidator validator;

    public HighflyStreamResolver(ResolverDefinition definition) {
        this(
                definition,
                DEFAULT_MANIFEST_URL,
                new TokenHttpClient(),
                defaultValidator()
        );
    }

    HighflyStreamResolver(ResolverDefinition definition, String manifestUrl) {
        this(
                definition,
                manifestUrl,
                new TokenHttpClient(),
                defaultValidator()
        );
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this(
                definition,
                "",
                httpClient,
                (uri, headers, listener) -> validator.validate(uri, headers, listener)
        );
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsCandidateValidator validator
    ) {
        this(definition, "", httpClient, validator);
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            String manifestUrl,
            TokenHttpClient httpClient,
            HlsCandidateValidator validator
    ) {
        if (definition == null) throw new NullPointerException("definition");
        if (httpClient == null) throw new NullPointerException("httpClient");
        if (validator == null) throw new NullPointerException("validator");
        this.definition = definition;
        this.configuredManifestUrl = isAllowedManifestUrl(manifestUrl)
                ? manifestUrl.trim()
                : "";
        this.httpClient = httpClient;
        this.validator = validator;
    }

    static boolean isAllowedManifestUrl(String value) {
        if (value == null || AppStrings.isBlank(value)) return false;
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "sports.highfly.to".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getRawPath() != null
                    && uri.getRawPath().endsWith("/manifest.json");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static HlsCandidateValidator defaultValidator() {
        HlsStreamValidator validator = new HlsStreamValidator();
        return (uri, headers, listener) -> validator.validate(uri, headers, listener);
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
        String stableId = channel == null || channel.getAttributes() == null
                ? ""
                : channel.getAttributes().get("x-resolver-stable-id");
        if (!AppStrings.isBlank(stableId)) return stableId.trim();
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
        List<ResolverPayloadParsers.HighflyCandidate> candidates;
        try {
            candidates = fetchCandidates(channel, progress, false);
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
            candidates = fetchCandidates(channel, progress, true);
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
            ResolutionProgressListener progress,
            boolean discoverCatalogAlternatives
    ) throws IOException {
        String slug = stableSlug(channel);
        if (AppStrings.isBlank(slug)) return Collections.emptyList();

        URI apiUri = streamApiUri(slug);
        URI activeManifestUri = null;
        String requestedManifest = definition.channelManifestUrl(channel);
        if (AppStrings.isBlank(requestedManifest)) requestedManifest = configuredManifestUrl;
        if (!AppStrings.isBlank(requestedManifest)) {
            try {
                URI manifestUri = manifestUri(requestedManifest);
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.PAGE_REQUEST,
                        "GET manifest.json · manifiesto Highfly"
                ));
                TokenHttpClient.Response manifestResponse = httpClient.getPublicOnHosts(
                        manifestUri.toString(),
                        highflyHeaders("application/json"),
                        maximumPayloadBytes(),
                        null,
                        STREAM_API_HOSTS
                );
                String manifestPayload = new String(
                        manifestResponse.getBody(),
                        java.nio.charset.StandardCharsets.UTF_8
                );
                if (!ResolverPayloadParsers.isHighflyStreamManifest(manifestPayload)) {
                    throw new IOException("El manifiesto Highfly no publica el recurso stream.");
                }
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.PAGE_PARSED,
                        "HTTP " + manifestResponse.getStatusCode()
                                + " · manifiesto Highfly válido"
                ));
                activeManifestUri = manifestUri;
                apiUri = streamResourceUri(manifestUri, "leaf:" + slug);
            } catch (IOException manifestError) {
                // A scoped manifest can disappear independently of the public
                // stream API. Keep the existing slug endpoint as a bounded
                // compatibility fallback instead of making playback depend on
                // a second metadata request.
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.SOURCE_BUILDING,
                        "Manifiesto Highfly no disponible · usando recurso de respaldo"
                ));
            }
        }

        List<ResolverPayloadParsers.HighflyCandidate> result = new ArrayList<>();
        IOException primaryError = null;
        try {
            result.addAll(fetchStreamCandidates(apiUri, progress));
        } catch (IOException error) {
            primaryError = error;
        }

        if (discoverCatalogAlternatives && activeManifestUri != null) {
            try {
                List<ResolverPayloadParsers.HighflyCatalogEntry> entries =
                        fetchCatalogEntries(activeManifestUri, channel, progress);
                for (ResolverPayloadParsers.HighflyCatalogEntry entry : entries) {
                    if (result.size() >= maximumStreams()) break;
                    if (entry.getId().equalsIgnoreCase("leaf:" + slug)) continue;
                    try {
                        result.addAll(fetchStreamCandidates(
                                streamResourceUri(activeManifestUri, entry.getId()),
                                progress
                        ));
                    } catch (IOException ignored) {
                        // One offline catalog entry must not hide the other
                        // variants that remain playable.
                    }
                }
            } catch (IOException ignored) {
                // The channel-specific stream endpoint remains authoritative
                // when the optional live catalog is unavailable.
            }
        }

        LinkedHashMap<String, ResolverPayloadParsers.HighflyCandidate> unique =
                new LinkedHashMap<>();
        for (ResolverPayloadParsers.HighflyCandidate candidate : result) {
            unique.put(candidate.getUri().toString(), candidate);
        }
        List<ResolverPayloadParsers.HighflyCandidate> sorted =
                new ArrayList<>(unique.values());
        sorted.sort((left, right) -> {
            int bitrate = Long.compare(
                    right.getAdvertisedBitrateBitsPerSecond(),
                    left.getAdvertisedBitrateBitsPerSecond()
            );
            return bitrate != 0 ? bitrate : 0;
        });
        if (sorted.isEmpty() && primaryError != null) throw primaryError;
        return sorted;
    }

    private List<ResolverPayloadParsers.HighflyCandidate> fetchStreamCandidates(
            URI apiUri,
            ResolutionProgressListener progress
    ) throws IOException {
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

    private List<ResolverPayloadParsers.HighflyCatalogEntry> fetchCatalogEntries(
            URI manifestUri,
            Channel channel,
            ResolutionProgressListener progress
    ) throws IOException {
        URI catalogUri = catalogResourceUri(manifestUri);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.CATALOG_REQUEST,
                "GET sports_live.json · catálogo Highfly"
        ));
        TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                catalogUri.toString(),
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
        List<String> identifiers = new ArrayList<>();
        if (channel != null) {
            identifiers.add(channel.getName());
            identifiers.add(channel.getTvgId());
        }
        identifiers.add(stableSlug(channel));
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.CATALOG_PARSED,
                "HTTP " + response.getStatusCode() + " · catálogo Highfly procesado"
        ));
        return ResolverPayloadParsers.parseHighflyCatalog(
                payload,
                identifiers,
                8
        );
    }

    private static URI manifestUri(String value) throws IOException {
        if (!isAllowedManifestUrl(value)) {
            throw new IOException("Manifiesto Highfly no permitido.");
        }
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("Manifiesto Highfly inválido.", error);
        }
    }

    private static URI streamResourceUri(URI manifestUri, String resourceId) throws IOException {
        if (manifestUri == null || AppStrings.isBlank(resourceId)
                || !resourceId.matches("[A-Za-z0-9_-]+:[A-Za-z0-9_-]{2,128}")) {
            throw new IOException("Recurso Highfly incompleto.");
        }
        return resourceUri(manifestUri, "stream/sport/" + resourceId + ".json");
    }

    static URI catalogResourceUri(URI manifestUri) throws IOException {
        return resourceUri(manifestUri, "catalog/sport/sports_live.json");
    }

    private static URI resourceUri(URI manifestUri, String resourcePathSuffix)
            throws IOException {
        if (manifestUri == null || AppStrings.isBlank(resourcePathSuffix)) {
            throw new IOException("Recurso Highfly incompleto.");
        }
        String path = manifestUri.getRawPath();
        int lastSlash = path == null ? -1 : path.lastIndexOf('/');
        if (lastSlash < 0) throw new IOException("Ruta de manifiesto Highfly inválida.");
        String prefix = path.substring(0, lastSlash);
        String resourcePath = prefix + "/" + resourcePathSuffix;
        if (resourcePath.startsWith("//")) resourcePath = resourcePath.substring(1);
        try {
            return URI.create(
                    "https://" + manifestUri.getHost() + resourcePath
            );
        } catch (IllegalArgumentException error) {
            throw new IOException("Recurso Highfly inválido.", error);
        }
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
        if (DynamicSourceReference.isAppOnly(channel.getStreamUri())) {
            throw cause == null
                    ? new IOException("Highfly no entregó una fuente reproducible.")
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
        if (configured != null && configured.matches("[A-Za-z0-9_-]{2,128}")) {
            return configured;
        }
        String reference = DynamicSourceReference.stableId(channel.getStreamUri());
        return reference.matches("[A-Za-z0-9_-]{2,128}") ? reference : "";
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
