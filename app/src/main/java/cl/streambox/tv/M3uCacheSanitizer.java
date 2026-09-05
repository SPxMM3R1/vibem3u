package cl.streambox.tv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Removes renewable provider credentials before a playlist is persisted. */
final class M3uCacheSanitizer {
    private static final Pattern TVG_ID_PATTERN = Pattern.compile(
            "\\btvg-id\\s*=\\s*\\\"([^\\\"]*)\\\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RESOLVER_PATTERN = Pattern.compile(
        "\\bx-resolver\\s*=\\s*\\\"([^\\\"]*)\\\"",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RESOLVER_ID_PATTERN = Pattern.compile(
            "\\bx-resolver-id\\s*=\\s*\\\"([^\\\"]*)\\\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PREMIUM_STABLE_PATTERN = Pattern.compile(
            "\\bx-highfly-premium-stable\\s*=\\s*\\\"true\\\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SAFE_HIGHFLY_SLUG = Pattern.compile(
            "(?i)^[a-z0-9][a-z0-9_-]{1,127}$"
    );
    private static final Set<String> LEGACY_TVVOO_IDS = setOf(
            "premiersports1.ie", "premiersports2.ie", "skysportsracing.uk"
    );
    private static final Set<String> SENSITIVE_QUERY_KEYS = setOf(
            "access_token", "token", "auth", "authorization", "signature",
            "sig", "key", "hdnea", "hdnts", "session", "sessionid"
    );
    private static final String TVVOO_PLACEHOLDER =
            "https://resolver.invalid/tvvoo.m3u8";
    private static final Pattern SENSITIVE_HTTP_ATTRIBUTE = Pattern.compile(
            "(?i)\\s+http-(?:cookie|authorization)\\s*=\\s*\"[^\"]*\""
    );

    private M3uCacheSanitizer() {}

    static String forDisk(String content) {
        if (content == null || content.isEmpty()) return content;

        StringBuilder result = new StringBuilder(content.length());
        String pendingTvgId = "";
        String pendingResolver = "";
        String pendingResolverId = "";
        boolean pendingPremiumStable = false;
        for (String rawLine : content.split("\\r?\\n", -1)) {
            String line = rawLine.trim();
            if (line.matches("(?i)^#EXTVLCOPT:http-(cookie|authorization)\\s*=.*")) {
                continue;
            }
            if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                pendingTvgId = extractTvgId(line);
                pendingResolver = extractResolver(line);
                pendingResolverId = extractResolverId(line);
                pendingPremiumStable = isPremiumStable(line, pendingResolver);
                result.append(SENSITIVE_HTTP_ATTRIBUTE.matcher(rawLine).replaceAll(""));
            } else if (!line.isEmpty() && !line.startsWith("#")
                    && pendingPremiumStable) {
                // A stable Premium list is allowed to carry only the public
                // leaf fallback. Canonicalizing it here also protects older
                // or compromised List 3 responses from leaving a signed path
                // in the persistent HTTP cache.
                result.append(canonicalHighflyStableUrl(pendingResolverId));
                pendingTvgId = "";
                pendingResolver = "";
                pendingResolverId = "";
                pendingPremiumStable = false;
            } else if (!line.isEmpty() && !line.startsWith("#")
                    && isTvVoo(pendingTvgId, pendingResolver)) {
                // TvVoo credentials are embedded in the path. Keeping only
                // the stable EXTINF metadata prevents a session URL from
                // becoming a persistent fallback.
                result.append(TVVOO_PLACEHOLDER);
                pendingTvgId = "";
                pendingResolver = "";
                pendingResolverId = "";
                pendingPremiumStable = false;
            } else if (!line.isEmpty() && !line.startsWith("#")
                    && isRenewableProvider(pendingTvgId, pendingResolver)) {
                result.append(stripSensitiveCredentials(rawLine));
                pendingTvgId = "";
                pendingResolver = "";
                pendingResolverId = "";
                pendingPremiumStable = false;
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

    private static String extractResolver(String line) {
        Matcher matcher = RESOLVER_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String extractResolverId(String line) {
        Matcher matcher = RESOLVER_ID_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static boolean isPremiumStable(String line, String resolver) {
        return "highfly".equalsIgnoreCase(resolver)
                && PREMIUM_STABLE_PATTERN.matcher(line).find();
    }

    private static String canonicalHighflyStableUrl(String resolverId) {
        String slug = resolverId == null ? "" : resolverId.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_HIGHFLY_SLUG.matcher(slug).matches()) {
            return "https://resolver.invalid/highfly-premium.m3u8";
        }
        return "https://leaf.highfly.dev/m3u/" + slug + "/live.m3u8";
    }

    private static boolean isRenewableProvider(String tvgId, String resolver) {
        return "0104".equalsIgnoreCase(tvgId)
                || "Meganoticias.cl".equalsIgnoreCase(tvgId)
                || "MeganoticiasAhora.cl".equalsIgnoreCase(tvgId)
                || !resolver.isBlank();
    }

    private static boolean isTvVoo(String tvgId, String resolver) {
        String normalized = tvgId == null ? "" : tvgId.trim().toLowerCase(Locale.ROOT);
        return "tvvoo".equalsIgnoreCase(resolver)
                || normalized.endsWith("@tvvoo")
                || LEGACY_TVVOO_IDS.contains(normalized);
    }

    private static String stripSensitiveCredentials(String value) {
        String trimmed = value.trim();
        int fragmentStart = trimmed.indexOf('#');
        String withoutFragment = fragmentStart < 0
                ? trimmed
                : trimmed.substring(0, fragmentStart);
        int queryStart = withoutFragment.indexOf('?');
        if (queryStart < 0) return withoutFragment;

        String base = withoutFragment.substring(0, queryStart);
        String query = withoutFragment.substring(queryStart + 1);
        List<String> kept = new ArrayList<>();
        for (String parameter : query.split("&")) {
            if (parameter.isBlank()) continue;
            int equals = parameter.indexOf('=');
            String key = (equals < 0 ? parameter : parameter.substring(0, equals))
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!SENSITIVE_QUERY_KEYS.contains(key)) kept.add(parameter);
        }
        if (kept.isEmpty()) return base;
        StringBuilder sanitizedQuery = new StringBuilder();
        for (String part : kept) {
            if (sanitizedQuery.length() > 0) sanitizedQuery.append('&');
            sanitizedQuery.append(part);
        }
        return base + "?" + sanitizedQuery;
    }

    private static Set<String> setOf(String... values) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        java.util.Collections.addAll(result, values);
        return java.util.Collections.unmodifiableSet(result);
    }
}
