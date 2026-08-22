package cl.streambox.tv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes renewable provider credentials before a playlist is persisted. */
final class M3uCacheSanitizer {
    private static final Pattern TVG_ID_PATTERN = Pattern.compile(
            "\\btvg-id\\s*=\\s*\\\"([^\\\"]*)\\\"",
            Pattern.CASE_INSENSITIVE
    );

    private M3uCacheSanitizer() {}

    static String forDisk(String content) {
        if (content == null || content.isEmpty()) return content;

        StringBuilder result = new StringBuilder(content.length());
        String pendingTvgId = "";
        for (String rawLine : content.split("\\r?\\n", -1)) {
            String line = rawLine.trim();
            if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                pendingTvgId = extractTvgId(line);
                result.append(rawLine);
            } else if (!line.isEmpty() && !line.startsWith("#") && isRenewableProvider(pendingTvgId)) {
                result.append(stripQueryAndFragment(rawLine));
                pendingTvgId = "";
            } else {
                result.append(rawLine);
            }
            result.append('\n');
        }
        if (result.length() > 0) result.setLength(result.length() - 1);
        return result.toString();
    }

    private static String extractTvgId(String line) {
        Matcher matcher = TVG_ID_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static boolean isRenewableProvider(String tvgId) {
        return "0104".equalsIgnoreCase(tvgId)
                || "MeganoticiasAhora.cl".equalsIgnoreCase(tvgId);
    }

    private static String stripQueryAndFragment(String value) {
        int queryStart = value.indexOf('?');
        int fragmentStart = value.indexOf('#');
        int credentialStart;
        if (queryStart < 0) {
            credentialStart = fragmentStart;
        } else if (fragmentStart < 0) {
            credentialStart = queryStart;
        } else {
            credentialStart = Math.min(queryStart, fragmentStart);
        }
        return credentialStart < 0 ? value : value.substring(0, credentialStart);
    }
}
