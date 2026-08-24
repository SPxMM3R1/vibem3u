package cl.streambox.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing and validation for the public provider player configurations. */
final class ProviderStreamParsers {
    private static final String TVN_DEFAULT_ID = "57a498c4d7b86d600e5461cb";
    private static final String MEGANOTICIAS_DEFAULT_ID = "561430ae330428c223687e1e";
    private static final int MAX_MEGA_CONFIG_BLOCK_LENGTH = 64 * 1024;
    private static final Pattern TVN_FIELD_PATTERN = Pattern.compile(
            "\\b(id|access_token)\\s*:\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern JSON_ACCESS_TOKEN_PATTERN = Pattern.compile(
            "\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
    );
    private static final Pattern MEGANOTICIAS_DECLARATION_PATTERN = Pattern.compile(
            "var\\s+VideoSenalEnVivo\\s*=\\s*\\{",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MEGANOTICIAS_ID_PATTERN = Pattern.compile(
            "\\bid\\s*:\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern MEGANOTICIAS_SERVER_KEY_PATTERN = Pattern.compile(
            "\\bserverKey\\s*:\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z0-9._~-]+"
    );
    private static final Pattern SAFE_STREAM_ID_PATTERN = Pattern.compile(
            "[A-Za-z0-9_-]+"
    );

    private ProviderStreamParsers() {}

    static TvnConfig parseTvn(String html) throws IOException {
        return parseTvn(html, "", "", TVN_DEFAULT_ID);
    }

    static TvnConfig parseTvn(
            String html,
            String configuredIdPattern,
            String configuredTokenPattern,
            String defaultId
    ) throws IOException {
        if (html == null || html.isBlank()) {
            throw new IOException("TVN no publico la configuracion del reproductor.");
        }

        String streamId = null;
        String accessToken = null;
        if ((configuredIdPattern == null || configuredIdPattern.isBlank())
                && (configuredTokenPattern == null || configuredTokenPattern.isBlank())) {
            Matcher fields = TVN_FIELD_PATTERN.matcher(html);
            while (fields.find()) {
                String field = fields.group(1);
                String value = fields.group(2);
                if ("id".equals(field)
                        && streamId == null
                        && SAFE_STREAM_ID_PATTERN.matcher(value).matches()) {
                    streamId = value;
                } else if ("access_token".equals(field) && accessToken == null) {
                    accessToken = value;
                }
                if (streamId != null && accessToken != null) break;
            }
        } else {
            streamId = firstCaptured(html, configuredIdPattern);
            accessToken = firstCaptured(html, configuredTokenPattern);
        }
        if (accessToken == null) {
            throw new IOException("TVN no publico la autorizacion del reproductor.");
        }
        String fallbackId = defaultId == null || defaultId.isBlank()
                ? TVN_DEFAULT_ID
                : defaultId.trim();
        return new TvnConfig(
                streamId == null || !SAFE_STREAM_ID_PATTERN.matcher(streamId).matches()
                        ? fallbackId
                        : streamId,
                validateToken(accessToken, "TVN")
        );
    }

    private static String firstCaptured(String input, String expression) throws IOException {
        if (expression == null || expression.isBlank()) return null;
        try {
            Matcher matcher = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(input);
            return matcher.find() && matcher.groupCount() >= 1
                    ? matcher.group(1).trim()
                    : null;
        } catch (RuntimeException error) {
            throw new IOException("El patrón del proveedor es inválido.", error);
        }
    }

    static MeganoticiasConfig parseMeganoticiasConfig(String html) throws IOException {
        if (html == null || html.isBlank()) {
            throw new IOException("Meganoticias no publico la configuracion del reproductor.");
        }

        Matcher declaration = MEGANOTICIAS_DECLARATION_PATTERN.matcher(html);
        if (!declaration.find()) {
            throw new IOException("Meganoticias no publico la configuracion del reproductor.");
        }
        int objectStart = declaration.end() - 1;
        int objectEnd = findObjectEnd(html, objectStart);
        if (objectEnd < 0) {
            throw new IOException("Meganoticias no publico la configuracion del reproductor.");
        }

        String configBlock = html.substring(objectStart, objectEnd);
        Matcher idMatcher = MEGANOTICIAS_ID_PATTERN.matcher(configBlock);
        Matcher serverKeyMatcher = MEGANOTICIAS_SERVER_KEY_PATTERN.matcher(configBlock);
        if (!idMatcher.find() || !serverKeyMatcher.find()) {
            throw new IOException("Meganoticias no publico la configuracion del reproductor.");
        }

        String configuredId = idMatcher.group(1).trim();
        String streamId = configuredId.isBlank()
                ? MEGANOTICIAS_DEFAULT_ID
                : configuredId;
        String serverKey = serverKeyMatcher.group(1).trim();
        if (serverKey.isEmpty()) {
            throw new IOException("Meganoticias no publico la autorizacion del reproductor.");
        }
        return new MeganoticiasConfig(streamId, serverKey);
    }

    static MeganoticiasConfig parseMeganoticiasConfig(
            String html,
            String configuredIdPattern,
            String configuredServerKeyPattern
    ) throws IOException {
        if ((configuredIdPattern == null || configuredIdPattern.isBlank())
                && (configuredServerKeyPattern == null
                || configuredServerKeyPattern.isBlank())) {
            return parseMeganoticiasConfig(html);
        }
        if (html == null || html.isBlank()) {
            throw new IOException("Meganoticias no publico la configuracion del reproductor.");
        }
        String streamId = firstCaptured(html, configuredIdPattern);
        String serverKey = firstCaptured(html, configuredServerKeyPattern);
        if (streamId == null || !SAFE_STREAM_ID_PATTERN.matcher(streamId).matches()
                || serverKey == null || serverKey.isBlank()) {
            throw new IOException("Meganoticias no publico la configuracion del reproductor.");
        }
        return new MeganoticiasConfig(streamId, serverKey);
    }

    private static int findObjectEnd(String html, int objectStart) {
        int limit = Math.min(html.length(), objectStart + MAX_MEGA_CONFIG_BLOCK_LENGTH);
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = objectStart; index < limit; index++) {
            char current = html.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '\"') {
                quote = current;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index + 1;
            }
        }
        return -1;
    }

    static String parseMeganoticiasAccessToken(String json) throws IOException {
        return parseMeganoticiasAccessToken(json, "access_token");
    }

    static String parseMeganoticiasAccessToken(String json, String tokenPath)
            throws IOException {
        if (json == null || json.isBlank()) {
            throw new IOException("Meganoticias devolvio una respuesta invalida.");
        }
        try {
            Object value = ResolverPayloadParsers.jsonValueAtPath(
                    new JSONObject(json),
                    tokenPath
            );
            if (value instanceof String) {
                return validateToken((String) value, "Meganoticias");
            }
        } catch (JSONException ignored) {
            // Compatibility fallback below accepts the original flat response.
        }
        if ("access_token".equals(tokenPath)) {
            Matcher matcher = JSON_ACCESS_TOKEN_PATTERN.matcher(json);
            if (matcher.find()) {
                return validateToken(matcher.group(1), "Meganoticias");
            }
        }
        throw new IOException("Meganoticias devolvio una respuesta invalida.");
    }

    private static String validateToken(String token, String provider) throws IOException {
        if (token == null || token.isBlank() || !SAFE_TOKEN_PATTERN.matcher(token).matches()) {
            throw new IOException(provider + " no publico un token valido.");
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
