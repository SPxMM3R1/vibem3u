package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;


/** Resolves Highfly from a configured manifest or its stable channel slug. */
public final class HighflyStreamResolver implements StreamResolver {
    private static final String DEFAULT_TEMPLATE =
            "https://papacito.cfd/m3u/{id}/live.m3u8";
    // Highfly is independent from VAVOO. Use the existing normal browser
    // identity used by the official-provider resolvers; VAVOO/2.6 is reserved
    // for TvVoo/Vavoo playback only.
    private static final String PLAYBACK_USER_AGENT = TokenHttpClient.BROWSER_USER_AGENT;
    private static final long DEFAULT_RESOLUTION_BUDGET_MILLIS = 12_000L;

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;

    public HighflyStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient(), new HlsStreamValidator());
    }

    HighflyStreamResolver(
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
        return definition.matchesExplicit(channel)
                || definition.matchesTvgId(channel)
                || definition.matchesHost(channel);
    }

    @Override public String stableSourceId(Channel channel) {
        String slug = slug(channel);
        return AppStrings.isBlank(slug) ? definition.stableSourceId(channel) : slug;
    }

    @Override public long cacheTtlMillis() { return definition.getCacheTtlMillis(); }

    @Override public boolean cacheResolvedSource() { return false; }

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

        String slug = slug(channel);
        if (AppStrings.isBlank(slug)) throw new IOException("Highfly no publicó un identificador estable.");

        // The configured leaf URL is the normal fast path. The manifest is a
        // recovery catalogue and should not add a network round trip to every
        // channel open while the direct source is healthy.
        LinkedHashSet<URI> candidates = new LinkedHashSet<>();
        String directTemplate = canonicalDirectTemplate(
                definition.getConfig("directTemplate", DEFAULT_TEMPLATE)
        );
        candidates.add(URI.create(directTemplate.replace("{id}", encodeSlug(slug))));
        if (channel != null && channel.getStreamUri() != null
                && "papacito.cfd".equalsIgnoreCase(channel.getStreamUri().getHost())) {
            candidates.add(channel.getStreamUri());
        }

        IOException lastError = null;
        int candidateNumber = 0;
        for (URI candidate : candidates) {
            progress.onProgress(ResolutionProgress.counted(
                    ResolutionStage.SOURCE_CANDIDATE,
                    ++candidateNumber,
                    candidates.size(),
                    "GET " + SafePlaybackText.url(candidate)
                            + " · playlist HLS · candidato configurado"
            ));
            try {
                URI accepted = validateCandidate(candidate, progress);
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.SOURCE_FOUND,
                        "Playlist HLS válida · GET " + SafePlaybackText.url(accepted)
                                + " · Media3 validará variante/segmento"
                ));
                return ResolvedPlaybackSource.dynamic(
                        getId(),
                        stableSourceId(channel),
                        accepted,
                        highflyHeaders("*/*"),
                        PLAYBACK_USER_AGENT,
                        expiresAt()
                );
            } catch (IOException error) {
                lastError = error;
            }
        }

        String manifestUrl = definition.channelManifestUrl(channel);
        if (!AppStrings.isBlank(manifestUrl)) {
            try {
                URI manifestUri = validManifestUri(manifestUrl);
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.PAGE_REQUEST,
                        "GET " + SafePlaybackText.url(manifestUri)
                                + " · JSON · manifiesto de recuperación"
                ));
                String manifest = httpClient.getText(
                        manifestUri.toString(),
                        highflyHeaders("application/json")
                );
                List<String> identifiers = new ArrayList<>();
                identifiers.add(slug);
                identifiers.add(channel.getTvgId());
                identifiers.add(channel.getName());
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.PAGE_PARSED,
                        "JSON válido · buscando slug/tvg-id/nombre"
                ));
                URI manifestCandidate = ResolverPayloadParsers.parseHighflyManifest(
                        manifest,
                        identifiers
                );
                if (manifestCandidate != null && candidates.add(manifestCandidate)) {
                    progress.onProgress(ResolutionProgress.counted(
                            ResolutionStage.SOURCE_CANDIDATE,
                            ++candidateNumber,
                            candidateNumber,
                            "GET " + SafePlaybackText.url(manifestCandidate)
                                    + " · playlist HLS · manifiesto"
                    ));
                    try {
                        URI accepted = validateCandidate(manifestCandidate, progress);
                        progress.onProgress(ResolutionProgress.of(
                                ResolutionStage.SOURCE_FOUND,
                                "Playlist HLS válida · GET " + SafePlaybackText.url(accepted)
                                        + " · Media3 validará variante/segmento"
                        ));
                        return ResolvedPlaybackSource.dynamic(
                                getId(),
                                stableSourceId(channel),
                                accepted,
                                highflyHeaders("*/*"),
                                PLAYBACK_USER_AGENT,
                                expiresAt()
                        );
                    } catch (IOException error) {
                        lastError = error;
                    }
                }
            } catch (IOException error) {
                lastError = error;
            }
        }
        throw new IOException("Highfly no entregó una fuente reproducible.", lastError);
    }

    private URI validateCandidate(URI candidate) throws IOException {
        return validateCandidate(candidate, ResolutionProgressListener.NONE);
    }

    private URI validateCandidate(
            URI candidate,
            ResolutionProgressListener listener
    ) throws IOException {
        if (candidate == null || candidate.getHost() == null) {
            throw new IOException("Highfly publicó una URL inválida.");
        }
        if ("https".equalsIgnoreCase(candidate.getScheme())
                && "papacito.cfd".equalsIgnoreCase(candidate.getHost())) {
            try {
                validator.validateForPlayback(candidate, highflyHeaders("*/*"), listener);
                return candidate;
            } catch (IOException error) {
                throw error;
            }
        }
        throw new IOException("Esquema Highfly no permitido.");
    }

    private static URI validManifestUri(String value) throws IOException {
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowedHost = "sports.highfly.to".equals(host)
                    || ("raw.githubusercontent.com".equals(host)
                    && uri.getPath().startsWith("/SPxMM3R1/lista-m3u/"));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHost) {
                throw new IOException("Manifiesto Highfly no permitido.");
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IOException("Manifiesto Highfly inválido.", error);
        }
    }

    private static String slug(Channel channel) {
        if (channel == null) return "";
        String configured = channel.getAttributes().get("x-resolver-id");
        if (configured != null && configured.matches("[A-Za-z0-9_-]{2,128}")) return configured;
        URI stream = channel.getStreamUri();
        if (stream == null || stream.getPath() == null) return "";
        String[] parts = stream.getPath().split("/");
        for (int index = 0; index + 1 < parts.length; index++) {
            if ("m3u".equalsIgnoreCase(parts[index])
                    && parts[index + 1].matches("[A-Za-z0-9_-]{2,128}")) {
                return parts[index + 1];
            }
        }
        return "";
    }

    private static String encodeSlug(String slug) throws IOException {
        if (!slug.matches("[A-Za-z0-9_-]{2,128}")) {
            throw new IOException("Slug Highfly inválido.");
        }
        return slug;
    }

    private static java.util.Map<String, String> highflyHeaders(String accept) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", PLAYBACK_USER_AGENT);
        headers.put("Referer", "https://sports.highfly.to/");
        headers.put("Origin", "https://sports.highfly.to");
        if (!AppStrings.isBlank(accept)) headers.put("Accept", accept);
        return Collections.unmodifiableMap(headers);
    }

    private static String canonicalDirectTemplate(String configured) {
        if (configured == null || AppStrings.isBlank(configured)) return DEFAULT_TEMPLATE;
        String value = configured.trim();
        if (value.startsWith("https://papacito.cfd/m3u/")
                && value.endsWith("/live.m3u8")
                && value.contains("{id}")) return value;
        return DEFAULT_TEMPLATE;
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
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
}
