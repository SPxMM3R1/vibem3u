package cl.streambox.tv;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing and validation for the public provider player configurations. */
final class ProviderStreamParsers {
    private static final String TVN_DEFAULT_ID = "57a498c4d7b86d600e5461cb";
    private static final String MEGANOTICIAS_DEFAULT_ID = "561430ae330428c223687e1e";
    private static final Pattern TVN_ID_PATTERN = Pattern.compile(
            "\\bid\\s*:\\s*['\"]([A-Za-z0-9_-]+)['\"]"
    );
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile(
            "\\baccess_token\\s*:\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern JSON_ACCESS_TOKEN_PATTERN = Pattern.compile(
            "\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
    );
    private static final Pattern MEGANOTICIAS_CONFIG_PATTERN = Pattern.compile(
            "var\\s+VideoSenalEnVivo\\s*=\\s*\\{\\s*"
                    + "id\\s*:\\s*['\"]([^'\"]+)['\"]"
                    + ".*?serverKey\\s*:\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z0-9._~-]+"
    );

    private ProviderStreamParsers() {}

    static TvnConfig parseTvn(String html) throws IOException {
        if (html == null || html.isBlank()) {
            throw new IOException("TVN no publico la configuración del reproductor.");
        }
        Matcher idMatcher = TVN_ID_PATTERN.matcher(html);
        String streamId = idMatcher.find() ? idMatcher.group(1) : TVN_DEFAULT_ID;
        Matcher tokenMatcher = ACCESS_TOKEN_PATTERN.matcher(html);
        if (!tokenMatcher.find()) {
            throw new IOException("TVN no publico la autorización del reproductor.");
        }
        String accessToken = validateToken(tokenMatcher.group(1), "TVN");
        return new TvnConfig(streamId, accessToken);
    }

    static MeganoticiasConfig parseMeganoticiasConfig(String html) throws IOException {
        if (html == null || html.isBlank()) {
            throw new IOException("Meganoticias no publico la configuración del reproductor.");
        }
        Matcher matcher = MEGANOTICIAS_CONFIG_PATTERN.matcher(html);
        if (!matcher.find()) {
            throw new IOException("Meganoticias no publico la configuración del reproductor.");
        }
        String streamId = matcher.group(1).isBlank()
                ? MEGANOTICIAS_DEFAULT_ID
                : matcher.group(1).trim();
        String serverKey = matcher.group(2).trim();
        if (serverKey.isEmpty()) {
            throw new IOException("Meganoticias no publico la autorización del reproductor.");
        }
        return new MeganoticiasConfig(streamId, serverKey);
    }

    static String parseMeganoticiasAccessToken(String json) throws IOException {
        if (json == null || json.isBlank()) {
            throw new IOException("Meganoticias devolvió una respuesta inválida.");
        }
        Matcher matcher = JSON_ACCESS_TOKEN_PATTERN.matcher(json);
        if (!matcher.find()) {
            throw new IOException("Meganoticias devolvió una respuesta inválida.");
        }
        return validateToken(matcher.group(1), "Meganoticias");
    }

    private static String validateToken(String token, String provider) throws IOException {
        if (token == null || token.isBlank() || !SAFE_TOKEN_PATTERN.matcher(token).matches()) {
            throw new IOException(provider + " no publicó un token válido.");
        }
        return token;
    }

    static final class TvnConfig {
        private final String streamId;
        private final String accessToken;

        TvnConfig(String streamId, String accessToken) {
            this.streamId = streamId;
            this.accessToken = accessToken;
        }

        String getStreamId() { return streamId; }
        String getAccessToken() { return accessToken; }
    }

    static final class MeganoticiasConfig {
        private final String streamId;
        private final String serverKey;

        MeganoticiasConfig(String streamId, String serverKey) {
            this.streamId = streamId;
            this.serverKey = serverKey;
        }

        String getStreamId() { return streamId; }
        String getServerKey() { return serverKey; }
    }
}
