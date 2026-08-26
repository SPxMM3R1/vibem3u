package cl.streambox.tv;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats resolver diagnostics without exposing session material.
 *
 * <p>The loading overlay is useful for diagnosing a provider, but it must not
 * become a token viewer. This class keeps the public part of an endpoint and
 * replaces sensitive query values and token-like path segments before text is
 * handed to the UI or to an error message.</p>
 */
final class SafePlaybackText {
    private static final String REDACTED = "[oculto]";
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)https?://[^\\s]+"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(\\b(?:access[_-]?token|server[_-]?key|token|key|signature|sig|"
                    + "auth(?:orization)?|hdnea|hdnts|session(?:id)?|jwt|ua)\\s*[=:]\\s*)"
                    + "([^&\\s,;]+)"
    );
    private static final Set<String> SENSITIVE_QUERY_KEYS = sensitiveQueryKeys();

    private SafePlaybackText() {}

    static String detail(String value) {
        if (value == null || value.isBlank()) return "";
        Matcher urls = URL_PATTERN.matcher(value);
        StringBuffer formatted = new StringBuffer();
        while (urls.find()) {
            String raw = urls.group();
            String trailing = trailingPunctuation(raw);
            String candidate = trailing.isEmpty()
                    ? raw
                    : raw.substring(0, raw.length() - trailing.length());
            String replacement = url(candidate) + trailing;
            urls.appendReplacement(formatted, Matcher.quoteReplacement(replacement));
        }
        urls.appendTail(formatted);

        Matcher assignments = SENSITIVE_ASSIGNMENT_PATTERN.matcher(formatted.toString());
        StringBuffer redacted = new StringBuffer();
        while (assignments.find()) {
            assignments.appendReplacement(
                    redacted,
                    Matcher.quoteReplacement(assignments.group(1) + REDACTED)
            );
        }
        assignments.appendTail(redacted);
        return redacted.toString();
    }

    static String url(URI uri) {
        if (uri == null) return "";
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            return redactUnparsed(uri.toString());
        }

        StringBuilder result = new StringBuilder();
        result.append(scheme.toLowerCase(Locale.ROOT)).append("://");
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            result.append('[').append(host).append(']');
        } else {
            result.append(host);
        }
        if (uri.getPort() > 0) result.append(':').append(uri.getPort());
        String path = uri.getRawPath();
        if (path != null && !path.isBlank()) result.append(redactPath(path));
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) result.append('?').append(redactQuery(query));
        // A fragment is not needed to identify a playback endpoint and can
        // carry provider session data, so it is intentionally omitted.
        return result.toString();
    }

    static String url(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return url(URI.create(value.trim()));
        } catch (IllegalArgumentException error) {
            return redactUnparsed(value.trim());
        }
    }

    private static String redactPath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder result = new StringBuilder(path.length());
        boolean redactNext = false;
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) result.append('/');
            String segment = segments[index];
            if (redactNext && !segment.isEmpty()) {
                segment = REDACTED;
                redactNext = false;
            }
            result.append(segment);
            if (isSensitivePathMarker(segment)) redactNext = true;
        }
        return result.toString();
    }

    private static String redactQuery(String query) {
        String[] parameters = query.split("&", -1);
        StringBuilder result = new StringBuilder(query.length());
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) result.append('&');
            String parameter = parameters[index];
            int equals = parameter.indexOf('=');
            if (equals < 0) {
                result.append(parameter);
                continue;
            }
            String rawKey = parameter.substring(0, equals);
            result.append(rawKey).append('=');
            if (isSensitiveQueryKey(rawKey)) {
                result.append(REDACTED);
            } else {
                result.append(parameter.substring(equals + 1));
            }
        }
        return result.toString();
    }

    private static boolean isSensitiveQueryKey(String rawKey) {
        String key = rawKey == null ? "" : rawKey.toLowerCase(Locale.ROOT);
        if (SENSITIVE_QUERY_KEYS.contains(key)) return true;
        try {
            String decoded = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                    .toLowerCase(Locale.ROOT);
            return SENSITIVE_QUERY_KEYS.contains(decoded);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSensitivePathMarker(String segment) {
        if (segment == null || segment.isBlank()) return false;
        String normalized;
        try {
            normalized = URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
                    .toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            normalized = segment.toLowerCase(Locale.ROOT);
        }
        return normalized.equals("token")
                || normalized.equals("access_token")
                || normalized.equals("access-token")
                || normalized.equals("serverkey")
                || normalized.equals("signature")
                || normalized.equals("sig")
                || normalized.equals("auth")
                || normalized.equals("authorization")
                || normalized.equals("session")
                || normalized.equals("sessionid")
                || normalized.equals("sunshine")
                || normalized.equals("bpk-token")
                || normalized.equals("hdnea")
                || normalized.equals("hdnts");
    }

    private static String redactUnparsed(String value) {
        String result = value == null ? "" : value;
        Matcher assignments = SENSITIVE_ASSIGNMENT_PATTERN.matcher(result);
        StringBuffer redacted = new StringBuffer();
        while (assignments.find()) {
            assignments.appendReplacement(
                    redacted,
                    Matcher.quoteReplacement(assignments.group(1) + REDACTED)
            );
        }
        assignments.appendTail(redacted);
        return redacted.toString();
    }

    private static String trailingPunctuation(String value) {
        int end = value == null ? 0 : value.length();
        while (end > 0 && ".,;:)]}>\"'…".indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(end);
    }

    private static Set<String> sensitiveQueryKeys() {
        Set<String> values = new HashSet<>();
        Collections.addAll(
                values,
                "access_token",
                "access-token",
                "token",
                "serverkey",
                "server_key",
                "key",
                "signature",
                "sig",
                "auth",
                "authorization",
                "hdnea",
                "hdnts",
                "session",
                "sessionid",
                "jwt",
                "ua",
                "url",
                "source",
                "stream",
                "streamurl",
                "hls"
        );
        return Collections.unmodifiableSet(values);
    }
}
