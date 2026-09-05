package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Resolves Highfly from a configured manifest or its stable channel slug. */
public final class HighflyStreamResolver implements StreamResolver {
    private static final String DEFAULT_TEMPLATE =
            "https://leaf.highfly.dev/m3u/{id}/live.m3u8";
    private static final long DEFAULT_RESOLUTION_BUDGET_MILLIS = 12_000L;

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;
    private final HighflyPremiumCatalogRepository premiumCatalogRepository;

    public HighflyStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient(), new HlsStreamValidator(), null);
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            HighflyPremiumCatalogRepository premiumCatalogRepository
    ) {
        this(definition, new TokenHttpClient(), new HlsStreamValidator(), premiumCatalogRepository);
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this(definition, httpClient, validator, null);
    }

    HighflyStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator,
            HighflyPremiumCatalogRepository premiumCatalogRepository
    ) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.validator = validator;
        this.premiumCatalogRepository = premiumCatalogRepository;
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
        return slug.isBlank() ? definition.stableSourceId(channel) : slug;
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

        boolean premiumStable = premiumCatalogRepository != null
                && premiumCatalogRepository.isPremiumStableChannel(channel);
        boolean temporaryEvent = premiumCatalogRepository != null
                && premiumCatalogRepository.isTemporaryEvent(channel);
        if (premiumCatalogRepository != null && (premiumStable || temporaryEvent)) {
            if (!premiumCatalogRepository.hasCredential() && temporaryEvent) {
                throw new IOException("El evento Premium requiere una credencial.");
            }
            if (premiumCatalogRepository.hasCredential()) {
                progress.onProgress(ResolutionProgress.of(
                        temporaryEvent
                                ? ResolutionStage.CATALOG_REQUEST
                                : ResolutionStage.SOURCE_REQUEST,
                        temporaryEvent
                                ? "Consultando catálogo Premium · verificando evento"
                                : "Solicitando fuente Premium · usando slug de Lista 3"
                ));
                try {
                    return premiumCatalogRepository.resolve(channel, progress);
                } catch (IOException error) {
                    if (temporaryEvent) {
                        throw new IOException("El evento Premium no está disponible.");
                    }
                    // Stable Premium entries deliberately retain the public
                    // leaf URL as a one-channel fallback. A temporary event
                    // never falls back to a fabricated placeholder.
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.SOURCE_CANDIDATE,
                            "Fuente Premium no disponible · probando respaldo"
                    ));
                }
            }
        }

        String slug = slug(channel);
        if (slug.isBlank()) throw new IOException("Highfly no publicó un identificador estable.");

        // The configured leaf URL is the normal fast path. The manifest is a
        // recovery catalogue and should not add a network round trip to every
        // channel open while the direct source is healthy.
        LinkedHashSet<URI> candidates = new LinkedHashSet<>();
        String directTemplate = definition.getConfig("directTemplate", DEFAULT_TEMPLATE);
        candidates.add(URI.create(directTemplate.replace("{id}", encodeSlug(slug))));
        if (channel != null && channel.getStreamUri() != null
                && "leaf.highfly.dev".equalsIgnoreCase(channel.getStreamUri().getHost())) {
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
                        Collections.emptyMap(),
                        TokenHttpClient.BROWSER_USER_AGENT,
                        expiresAt()
                );
            } catch (IOException error) {
                lastError = error;
            }
        }

        String manifestUrl = definition.channelManifestUrl(channel);
        if (!manifestUrl.isBlank()) {
            try {
                URI manifestUri = validManifestUri(manifestUrl);
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.PAGE_REQUEST,
                        "GET " + SafePlaybackText.url(manifestUri)
                                + " · JSON · manifiesto de recuperación"
                ));
                String manifest = httpClient.getText(
                        manifestUri.toString(),
                        Collections.singletonMap("Accept", "application/json")
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
                                Collections.emptyMap(),
                                TokenHttpClient.BROWSER_USER_AGENT,
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

    @Override
    public void clearSensitiveState() {
        if (premiumCatalogRepository != null) premiumCatalogRepository.clearSession();
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
        if ("https".equalsIgnoreCase(candidate.getScheme())) {
            try {
                validator.validateForPlayback(candidate, Collections.emptyMap(), listener);
                return candidate;
            } catch (IOException error) {
                if (!definition.getBooleanConfig("allowHttpFallback", false)
                        || !"leaf.highfly.dev".equalsIgnoreCase(candidate.getHost())
                        || !isCertificateFailure(error)) throw error;
                URI fallback = URI.create(candidate.toString().replaceFirst("^https://", "http://"));
                listener.onProgress(ResolutionProgress.of(
                        ResolutionStage.SOURCE_CANDIDATE,
                        "TLS rechazado · probando HTTP " + SafePlaybackText.url(fallback)
                ));
                validator.validateForPlayback(fallback, Collections.emptyMap(), listener);
                return fallback;
            }
        }
        if ("http".equalsIgnoreCase(candidate.getScheme())
                && "leaf.highfly.dev".equalsIgnoreCase(candidate.getHost())) {
            validator.validateForPlayback(candidate, Collections.emptyMap(), listener);
            return candidate;
        }
        throw new IOException("Esquema Highfly no permitido.");
    }

    private static URI validManifestUri(String value) throws IOException {
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowedHost = "sports.highfly.dev".equals(host)
                    || "leaf.highfly.dev".equals(host)
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

    private static boolean isCertificateFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof SSLHandshakeException
                    || current instanceof SSLPeerUnverifiedException
                    || current instanceof CertificateException) return true;
            current = current.getCause();
        }
        return false;
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
