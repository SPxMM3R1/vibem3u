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
        return slug.isBlank() ? definition.stableSourceId(channel) : slug;
    }

    @Override public long cacheTtlMillis() { return definition.getCacheTtlMillis(); }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        String slug = slug(channel);
        if (slug.isBlank()) throw new IOException("Highfly no publicó un identificador estable.");

        LinkedHashSet<URI> candidates = new LinkedHashSet<>();
        String manifestUrl = definition.channelManifestUrl(channel);
        IOException manifestError = null;
        if (!manifestUrl.isBlank()) {
            try {
                URI manifestUri = validManifestUri(manifestUrl);
                List<String> identifiers = new ArrayList<>();
                identifiers.add(slug);
                identifiers.add(channel.getTvgId());
                identifiers.add(channel.getName());
                candidates.add(ResolverPayloadParsers.parseHighflyManifest(
                        httpClient.getText(
                                manifestUri.toString(),
                                Collections.singletonMap("Accept", "application/json")
                        ),
                        identifiers
                ));
            } catch (IOException error) {
                manifestError = error;
            }
        }

        String directTemplate = definition.getConfig("directTemplate", DEFAULT_TEMPLATE);
        candidates.add(URI.create(directTemplate.replace("{id}", encodeSlug(slug))));
        if (channel != null && channel.getStreamUri() != null
                && "leaf.highfly.dev".equalsIgnoreCase(channel.getStreamUri().getHost())) {
            candidates.add(channel.getStreamUri());
        }

        IOException lastError = manifestError;
        for (URI candidate : candidates) {
            try {
                URI accepted = validateCandidate(candidate);
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
        throw new IOException("Highfly no entregó una fuente reproducible.", lastError);
    }

    private URI validateCandidate(URI candidate) throws IOException {
        if (candidate == null || candidate.getHost() == null) {
            throw new IOException("Highfly publicó una URL inválida.");
        }
        if ("https".equalsIgnoreCase(candidate.getScheme())) {
            try {
                validator.validate(candidate, Collections.emptyMap());
                return candidate;
            } catch (IOException error) {
                if (!definition.getBooleanConfig("allowHttpFallback", false)
                        || !"leaf.highfly.dev".equalsIgnoreCase(candidate.getHost())
                        || !isCertificateFailure(error)) throw error;
                URI fallback = URI.create(candidate.toString().replaceFirst("^https://", "http://"));
                validator.validate(fallback, Collections.emptyMap());
                return fallback;
            }
        }
        if ("http".equalsIgnoreCase(candidate.getScheme())
                && "leaf.highfly.dev".equalsIgnoreCase(candidate.getHost())) {
            validator.validate(candidate, Collections.emptyMap());
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
}
