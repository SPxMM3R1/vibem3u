package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;

/** Reglas del token de Highfly Premium: solo un segmento de ruta seguro. */
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
     * Acepta el token solo o el enlace del manifiesto que entrega
     * premium.highfly.to/configure, con o sin el tramo de configuración:
     * {@code https://premium.highfly.to/<token>[/<config>]/manifest.json}. Del enlace
     * solo se conserva el token y la región; la configuración no se guarda.
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
                || uri.getQuery() != null) {
            throw new IOException("La credencial Premium no tiene un formato válido.");
        }
        HighflyPremiumRegion region = HighflyPremiumRegion.fromHost(uri.getHost());
        if (region == null) throw new IOException("El enlace no pertenece a Highfly Premium.");
        String rawPath = uri.getRawPath();
        String[] segments = rawPath == null ? new String[0] : rawPath.split("/");
        if (segments.length < 2) {
            throw new IOException("El enlace Premium no contiene un token válido.");
        }
        String token = segments[1];
        if (!isValid(token)) throw new IOException("El enlace Premium no contiene un token válido.");
        return new ParsedInput(token, region);
    }

    static boolean isValid(String value) {
        if (value == null || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return false;
        }
        // El proveedor usa el token como un segmento de ruta. Rechazar separadores,
        // escapes, comillas y controles impide que un valor pegado cambie el endpoint.
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
        return !".".equals(value) && !"..".equals(value);
    }

    static final class ParsedInput {
        private final String token;
        private final HighflyPremiumRegion region;

        ParsedInput(String token, HighflyPremiumRegion region) {
            this.token = token;
            this.region = region;
        }

        String getToken() {
            return token;
        }

        /** Región del enlace pegado, o null si se pegó solo el token. */
        HighflyPremiumRegion getRegion() {
            return region;
        }
    }
}
