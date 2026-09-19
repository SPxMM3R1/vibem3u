package cl.streambox.tv;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private static final Pattern RESOLVER_ALIASES_PATTERN = Pattern.compile(
            "\\bx-resolver-ids\\s*=\\s*\\\"([^\\\"]*)\\\"",
            Pattern.CASE_INSENSITIVE
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
        String pendingResolverAliases = "";
        for (String rawLine : content.split("\\r?\\n", -1)) {
            String line = rawLine.trim();
            if (line.matches("(?i)^#EXTVLCOPT:http-(cookie|authorization)\\s*=.*")) {
                continue;
            }
            if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                pendingTvgId = extractTvgId(line);
                pendingResolver = extractResolver(line);
                pendingResolverId = extractResolverId(line);
                pendingResolverAliases = extractResolverAliases(line);
                result.append(SENSITIVE_HTTP_ATTRIBUTE.matcher(rawLine).replaceAll(""));
            } else if (!line.isEmpty() && !line.startsWith("#")
                    && isTvVoo(pendingTvgId, pendingResolver)) {
                // TvVoo credentials are embedded in the path. Keep a
                // tokenless resolver reference so the app renews the source.
                result.append(tvvooReference(
                        line,
                        pendingTvgId,
                        pendingResolverId,
                        pendingResolverAliases
                ));
                pendingTvgId = "";
                pendingResolver = "";
                pendingResolverId = "";
                pendingResolverAliases = "";
            } else if (!line.isEmpty() && !line.startsWith("#")
                    && isRenewableProvider(pendingTvgId, pendingResolver)) {
                result.append(stripSensitiveCredentials(rawLine));
                pendingTvgId = "";
                pendingResolver = "";
                pendingResolverId = "";
                pendingResolverAliases = "";
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

    private static String extractResolverAliases(String line) {
        Matcher matcher = RESOLVER_ALIASES_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String tvvooReference(
            String originalLine,
            String tvgId,
            String resolverId,
            String resolverAliases
    ) {
        if (isSafeTvVooReference(originalLine)) return originalLine;
        if (!TvVooCatalogChannel.isStableId(tvgId)) return TVVOO_PLACEHOLDER;
        boolean hasSafeAlias = TvVooSourceHistory.isSafeAlias(resolverId);
        if (!hasSafeAlias && !AppStrings.isBlank(resolverAliases)) {
            for (String alias : resolverAliases.split(";")) {
                if (TvVooSourceHistory.isSafeAlias(alias)) {
                    hasSafeAlias = true;
                    break;
                }
            }
        }
        // A legacy row with only a tvg-id or opaque alias cannot be renewed
        // deterministically. Preserve the old placeholder for compatibility.
        if (!hasSafeAlias || AppStrings.isBlank(tvgId)) return TVVOO_PLACEHOLDER;
        try {
            return "tvvoo://channel/" + URLEncoder.encode(
                    tvgId.trim(),
                    StandardCharsets.UTF_8.name()
            ).replace("+", "%20");
        } catch (Exception ignored) {
            return TVVOO_PLACEHOLDER;
        }
    }

    private static boolean isSafeTvVooReference(String value) {
        if (AppStrings.isBlank(value)) return false;
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            String path = uri.getRawPath();
            return "tvvoo".equalsIgnoreCase(uri.getScheme())
                    && "channel".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && path != null
                    && path.startsWith("/")
                    && path.length() > 1
                    && path.indexOf('/', 1) < 0
                    && TvVooCatalogChannel.isStableId(decodeOnce(path.substring(1)));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String decodeOnce(String value) {
        try {
            return URLDecoder.decode(
                    value.replace("+", "%2B"),
                    StandardCharsets.UTF_8.name()
            );
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static boolean isRenewableProvider(String tvgId, String resolver) {
        return "0104".equalsIgnoreCase(tvgId)
                || "Meganoticias.cl".equalsIgnoreCase(tvgId)
                || "MeganoticiasAhora.cl".equalsIgnoreCase(tvgId)
                || !AppStrings.isBlank(resolver);
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
            if (AppStrings.isBlank(parameter)) continue;
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
