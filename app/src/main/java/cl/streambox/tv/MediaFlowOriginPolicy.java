package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Validates the user-selected MediaFlow origin and keeps requests on it.
 *
 * <p>The origin is an explicit user setting, so an RFC1918/LAN address is
 * allowed here. The normal resolver policy remains public-only for all other
 * providers. Exact scheme, host and port matching is intentional: a
 * redirect to a different origin must never inherit the MediaFlow password.
 */
public final class MediaFlowOriginPolicy {
    private MediaFlowOriginPolicy() {}

    public static URI parseOrigin(String value) throws IOException {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("Origen MediaFlow inválido.", error);
        }
        if (uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getRawPath() != null
                && !uri.getRawPath().isEmpty()
                && !"/".equals(uri.getRawPath()))) {
            throw new IOException("Origen MediaFlow inválido.");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IOException("Puerto MediaFlow inválido.");
        }
        try {
            return new URI(
                    uri.getScheme().toLowerCase(Locale.ROOT),
                    null,
                    uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort(),
                    null,
                    null,
                    null
            );
        } catch (java.net.URISyntaxException error) {
            throw new IOException("Origen MediaFlow inválido.", error);
        }
    }

    public static boolean sameOrigin(URI candidate, URI configuredOrigin) {
        if (candidate == null || configuredOrigin == null
                || candidate.getHost() == null || configuredOrigin.getHost() == null) {
            return false;
        }
        return equalsIgnoreCase(candidate.getScheme(), configuredOrigin.getScheme())
                && equalsIgnoreCase(candidate.getHost(), configuredOrigin.getHost())
                && effectivePort(candidate) == effectivePort(configuredOrigin);
    }

    public static void requireSameOrigin(URI candidate, URI configuredOrigin)
            throws IOException {
        if (candidate == null || candidate.getUserInfo() != null
                || candidate.getFragment() != null
                || candidate.getHost() == null) {
            throw new IOException("URL MediaFlow inválida.");
        }
        if (!sameOrigin(candidate, configuredOrigin)) {
            throw new IOException("MediaFlow redirigió fuera del origen configurado.");
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
