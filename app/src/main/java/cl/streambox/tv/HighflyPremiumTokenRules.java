package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;

/** Validation rules for the token path used by the Highfly Premium service. */
final class HighflyPremiumTokenRules {
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 256;

    private HighflyPremiumTokenRules() {}

    static String normalize(String value) throws IOException {
        String token = value == null ? "" : value.trim();
        if (!isValid(token)) {
            throw new IOException("La credencial Premium no tiene un formato válido.");
        }
        return token;
    }

    /**
     * Accepts the raw token or a manifest URL copied from Highfly's Stremio
     * configuration page. The URL form is reduced to its first path segment
     * immediately; no URL or configuration path is persisted.
     */
    static ParsedInput parseInput(String value) throws IOException {
        String input = value == null ? "" : value.trim();
        if (isValid(input)) return new ParsedInput(input, null);

        URI uri;
        try {
            uri = URI.create(input);
        } catch (IllegalArgumentException error) {
            throw new IOException("La credencial Premium no tiene un formato válido.");
        }
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "stremio".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IOException("La credencial Premium no tiene un formato válido.");
        }

        HighflyPremiumPreferences.Region region =
                HighflyPremiumPreferences.Region.fromHost(uri.getHost());
        if (region == null) {
            throw new IOException("El enlace Premium no pertenece a Highfly.");
        }

        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.endsWith("/manifest.json")) {
            throw new IOException("El enlace Premium no es un manifest válido.");
        }
        String[] pathSegments = rawPath.split("/");
        if (pathSegments.length < 3) {
            throw new IOException("El enlace Premium no contiene un token válido.");
        }
        String token = pathSegments[1];
        if (!isValid(token)) {
            throw new IOException("El enlace Premium no contiene un token válido.");
        }
        return new ParsedInput(token, region);
    }

    static boolean isValid(String value) {
        if (value == null || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return false;
        }
        // The provider uses the token as one URL path segment. Rejecting path
        // delimiters, percent escapes, quotes and control characters prevents
        // a pasted value from changing the endpoint that receives it.
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean safe = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '_'
                    || character == '-'
                    || character == '~'
                    || character == '+'
                    || character == '=';
            if (!safe) return false;
        }
        return true;
    }

    static final class ParsedInput {
        private final String token;
        private final HighflyPremiumPreferences.Region region;

        ParsedInput(String token, HighflyPremiumPreferences.Region region) {
            this.token = token;
            this.region = region;
        }

        String getToken() {
            return token;
        }

        HighflyPremiumPreferences.Region getRegion() {
            return region;
        }
    }
}
