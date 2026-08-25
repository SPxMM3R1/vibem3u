package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Resolves short-lived TvVoo candidates from stable catalogue aliases. */
public final class TvVooStreamResolver implements StreamResolver {
    private static final String DEFAULT_ENDPOINT = "https://tvvoo.hayd.uk/stream/tv";
    static final String PLAYBACK_USER_AGENT = "VAVOO/2.6";

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;
    private final StreamResolver directFallback;
    private final TvVooResolutionMode resolutionMode;

    public TvVooStreamResolver(ResolverDefinition definition) {
        this(definition, TvVooResolutionMode.BOTH);
    }

    public TvVooStreamResolver(
            ResolverDefinition definition,
            TvVooResolutionMode resolutionMode
    ) {
        TokenHttpClient fastClient = new TokenHttpClient(4_000, 6_000);
        this.definition = definition;
        this.httpClient = fastClient;
        this.validator = new HlsStreamValidator(fastClient);
        this.directFallback = new VavooStreamResolver(definition);
        this.resolutionMode = resolutionMode == null
                ? TvVooResolutionMode.BOTH
                : resolutionMode;
    }

    TvVooStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this(
                definition,
                httpClient,
                validator,
                new VavooStreamResolver(definition),
                TvVooResolutionMode.BOTH
        );
    }

    TvVooStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator,
            StreamResolver directFallback,
            TvVooResolutionMode resolutionMode
    ) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.validator = validator;
        this.directFallback = directFallback;
        this.resolutionMode = resolutionMode == null
                ? TvVooResolutionMode.BOTH
                : resolutionMode;
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

    @Override public long cacheTtlMillis() { return definition.getCacheTtlMillis(); }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        if (!resolutionMode.usesExternalResolver()) {
            return resolveDirect(channel, null);
        }

        String endpointBase = channel.getAttributes().get("x-resolver-endpoint");
        if (endpointBase == null || endpointBase.isBlank()) {
            endpointBase = definition.getConfig("endpointBase", DEFAULT_ENDPOINT);
        }
        endpointBase = validEndpoint(endpointBase);

        LinkedHashSet<String> aliases = new LinkedHashSet<>(definition.resolverAliases(channel));
        // Explicit M3U aliases are authoritative. Generated aliases are only
        // compatibility fallbacks; querying both delays the direct engine
        // behind unrelated dead candidates.
        if (aliases.isEmpty()) aliases.addAll(generatedAliases(channel));
        int maxAliases = definition.getIntConfig("maxAliases", 8, 1, 12);
        int maxCandidates = definition.getIntConfig("maxCandidates", 16, 1, 32);
        boolean allowHttpFallback = definition.getBooleanConfig("allowHttpFallback", true);
        Map<String, String> jsonHeaders = Collections.singletonMap("Accept", "application/json");
        Map<String, String> playbackHeaders = playbackHeaders();

        int aliasCount = 0;
        int candidateCount = 0;
        IOException lastError = null;
        for (String alias : aliases) {
            if (++aliasCount > maxAliases) break;
            try {
                String endpoint = endpointBase + "/" + encodedAlias(alias) + ".json";
                List<URI> candidates = ResolverPayloadParsers.parseTvVooCandidates(
                        httpClient.getText(endpoint, jsonHeaders),
                        definition.getConfig("streamsPath", "streams"),
                        definition.getConfig("urlField", "url")
                );
                for (URI candidate : candidates) {
                    if (++candidateCount > maxCandidates) break;
                    try {
                        URI accepted = validateCandidate(
                                validator,
                                candidate,
                                allowHttpFallback,
                                playbackHeaders
                        );
                        return ResolvedPlaybackSource.dynamic(
                                getId(),
                                stableSourceId(channel),
                                accepted,
                                playbackHeaders,
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
            if (candidateCount >= maxCandidates) break;
        }
        if (resolutionMode.usesDirectResolver()) {
            return resolveDirect(channel, lastError);
        }
        throw new IOException("TvVoo no entregó una fuente reproducible.", lastError);
    }

    private ResolvedPlaybackSource resolveDirect(
            Channel channel,
            IOException externalError
    ) throws IOException {
        try {
            return directFallback.resolve(channel);
        } catch (IOException directError) {
            if (externalError != null) directError.addSuppressed(externalError);
            String message = resolutionMode == TvVooResolutionMode.DIRECT_ONLY
                    ? "Vavoo directo no entregó una fuente reproducible."
                    : "TvVoo y Vavoo directo no entregaron una fuente reproducible.";
            throw new IOException(message, directError);
        }
    }

    @Override
    public void clearSensitiveState() {
        directFallback.clearSensitiveState();
    }

    static URI validateCandidate(
            HlsStreamValidator validator,
            URI published,
            boolean allowHttpFallback,
            Map<String, String> playbackHeaders
    ) throws IOException {
        String scheme = published.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            try {
                validator.validate(published, playbackHeaders);
                return published;
            } catch (IOException error) {
                if (!allowHttpFallback || !isExpiredCertificateFailure(error)) throw error;
                URI fallback = withScheme(published, "http");
                validator.validate(fallback, playbackHeaders);
                return fallback;
            }
        }
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IOException("TvVoo publicó una URL inválida.");
        }
        URI upgraded = withScheme(published, "https");
        try {
            validator.validate(upgraded, playbackHeaders);
            return upgraded;
        } catch (IOException error) {
            if (!allowHttpFallback || !isExpiredCertificateFailure(error)) throw error;
            validator.validate(published, playbackHeaders);
            return published;
        }
    }

    static Map<String, String> playbackHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", PLAYBACK_USER_AGENT);
        headers.put("Referer", "https://vavoo.to/");
        headers.put("Origin", "https://vavoo.to");
        return Collections.unmodifiableMap(headers);
    }

    private static List<String> generatedAliases(Channel channel) {
        if (channel == null) return Collections.emptyList();
        String name = channel.getAttributes().get("tvg-name");
        if (name == null || name.isBlank()) name = channel.getName();
        String country = channel.getAttributes().get("tvg-country");
        if (name == null || name.isBlank() || country == null || country.isBlank()) {
            return Collections.emptyList();
        }
        String normalizedName = name.trim().toUpperCase(Locale.ROOT);
        String group = country.trim().toLowerCase(Locale.ROOT);
        List<String> aliases = new ArrayList<>();
        aliases.add("vavoo_" + encodePart(normalizedName + "|group:" + group));
        aliases.add("vavoo_" + encodePart(normalizedName + " HD|group:" + group));
        aliases.add("vavoo_" + encodePart(normalizedName + " FHD|group:" + group));
        return aliases;
    }

    private static String validEndpoint(String value) throws IOException {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"tvvoo.hayd.uk".equalsIgnoreCase(uri.getHost())
                    || !uri.getPath().startsWith("/stream/tv")) {
                throw new IOException("Endpoint TvVoo no permitido.");
            }
            String result = uri.toString();
            return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
        } catch (IllegalArgumentException error) {
            throw new IOException("Endpoint TvVoo inválido.", error);
        }
    }

    private static String encodedAlias(String alias) throws IOException {
        String value = alias == null ? "" : alias.trim();
        if (value.matches("vavoo_[A-Za-z0-9%._~+\\-]+")) return value;
        if (value.startsWith("vavoo_")) {
            return "vavoo_" + encodePart(value.substring("vavoo_".length()));
        }
        throw new IOException("Alias TvVoo inválido.");
    }

    private static String encodePart(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static URI withScheme(URI uri, String scheme) throws IOException {
        try {
            return new URI(
                    scheme,
                    uri.getUserInfo(),
                    uri.getHost(),
                    -1,
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
        } catch (Exception error) {
            throw new IOException("TvVoo publicó una URL inválida.", error);
        }
    }

    static boolean isExpiredCertificateFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof CertificateExpiredException) return true;
            if (current instanceof SSLHandshakeException
                    || current instanceof SSLPeerUnverifiedException
                    || current instanceof CertificateException) {
                String message = current.getMessage();
                String normalized = message == null
                        ? ""
                        : message.toLowerCase(Locale.ROOT);
                if (normalized.contains("expired") || normalized.contains("notafter")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
    }
}
