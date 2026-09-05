package cl.streambox.tv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Explicit M3U HTTP options, scoped to one channel and never inferred from its token. */
final class ChannelRequestHeaders {
    private ChannelRequestHeaders() {}

    static Map<String, String> from(Channel channel) {
        if (channel == null) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> option : channel.getAttributes().entrySet()) {
            String name = headerName(option.getKey());
            String value = safeValue(option.getValue());
            if (name != null && value != null) result.put(name, value);
        }
        return Collections.unmodifiableMap(result);
    }

    static String userAgent(Channel channel, String fallback) {
        String configured = from(channel).get("User-Agent");
        return configured == null ? fallback : configured;
    }

    static void parseOption(String line, Map<String, String> attributes) {
        if (!line.regionMatches(true, 0, "#EXTVLCOPT:", 0, 11)) return;
        int equals = line.indexOf('=', 11);
        if (equals < 0) return;
        String option = line.substring(11, equals).trim().toLowerCase(Locale.ROOT);
        String value = safeValue(line.substring(equals + 1));
        if (headerName(option) != null && value != null) attributes.put(option, value);
    }

    static String headerName(String option) {
        if (option == null) return null;
        return switch (option.toLowerCase(Locale.ROOT)) {
            case "http-user-agent" -> "User-Agent";
            case "http-referrer", "http-referer" -> "Referer";
            case "http-origin" -> "Origin";
            case "http-cookie" -> "Cookie";
            case "http-authorization" -> "Authorization";
            default -> null;
        };
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank() || value.length() > 8192) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) return null;
        }
        return value.trim();
    }
}
