package cl.streambox.tv;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenless, app-only references for providers whose playback URL is
 * generated at open time. These references are never sent to Media3: the
 * resolver registry consumes them before playback is created.
 */
final class DynamicSourceReference {
    static final String SCHEME = "vibem3u";
    private static final String HOST = "resolver";
    private static final Pattern HIGHFLY_SLUG = Pattern.compile(
            "^/m3u/([A-Za-z0-9_-]{2,128})/live\\.m3u8$",
            Pattern.CASE_INSENSITIVE
    );

    private DynamicSourceReference() {}

    /**
     * Replaces a known dynamic provider URL with a stable internal reference.
     * TvVoo keeps its existing tvvoo:// contract because it carries the full
     * stable catalogue identity and aliases.
     */
    static URI normalize(URI source, Map<String, String> attributes) {
        if (source == null) return null;
        if (isAppOnly(source)) return source;
        String provider = providerFor(source, attributes);
        if (provider == null || "tvvoo".equals(provider)) return null;
        String stableId = stableIdFor(source, attributes, provider);
        if (AppStrings.isBlank(stableId)) return null;
        return create(provider, stableId);
    }

    /** Adds explicit resolver metadata when a legacy row was identified only by its URL. */
    static void enrichAttributes(URI source, Map<String, String> attributes) {
        if (source == null || attributes == null) return;
        String provider = providerFor(source, attributes);
        if (provider == null) return;
        if (AppStrings.isBlank(attributes.get("x-resolver"))) {
            attributes.put("x-resolver", provider);
        }
        if ("highfly".equals(provider)
                && AppStrings.isBlank(attributes.get("x-resolver-id"))) {
            String slug = stableIdFor(source, attributes, provider);
            if (isHighflySlug(slug)) attributes.put("x-resolver-id", slug);
        }
    }

    static String providerFor(URI source, Map<String, String> attributes) {
        String explicit = value(attributes, "x-resolver").toLowerCase(Locale.ROOT);
        if (!AppStrings.isBlank(explicit)) {
            return isKnownProvider(explicit) ? explicit : null;
        }
        if (isAppOnly(source)) return provider(source);

        String tvgId = value(attributes, "tvg-id");
        if ("0104".equalsIgnoreCase(tvgId)) return "tvn";
        if ("Meganoticias.cl".equalsIgnoreCase(tvgId)
                || "MeganoticiasAhora.cl".equalsIgnoreCase(tvgId)) {
            return "meganoticias";
        }
        if (tvgId.toLowerCase(Locale.ROOT).endsWith("@tvvoo")) return "tvvoo";
        if (isHighflyTvgId(tvgId)) return "highfly";

        String host = source == null || source.getHost() == null
                ? ""
                : source.getHost().toLowerCase(Locale.ROOT);
        if ("leaf.highfly.dev".equals(host) || "papacito.cfd".equals(host)) {
            return "highfly";
        }
        return null;
    }

    static String stableIdFor(URI source, Map<String, String> attributes, String provider) {
        if (isAppOnly(source) && provider.equals(provider(source))) {
            return stableId(source);
        }
        String configured = value(attributes, "x-resolver-id");
        if (!AppStrings.isBlank(configured)) return configured;
        if ("highfly".equals(provider)) {
            String slug = highflySlug(source);
            if (!AppStrings.isBlank(slug)) return slug;
        }
        return value(attributes, "tvg-id");
    }

    static boolean isAppOnly(URI source) {
        return source != null
                && SCHEME.equalsIgnoreCase(source.getScheme())
                && HOST.equalsIgnoreCase(source.getHost())
                && source.getUserInfo() == null
                && source.getPort() == -1
                && source.getRawQuery() == null
                && source.getRawFragment() == null
                && validReferencePath(source.getRawPath());
    }

    static boolean isInternalScheme(URI source) {
        return source != null && SCHEME.equalsIgnoreCase(source.getScheme());
    }

    static String provider(URI source) {
        if (!isAppOnly(source)) return "";
        String[] segments = rawSegments(source.getRawPath());
        return decode(segments[0]);
    }

    static String stableId(URI source) {
        if (!isAppOnly(source)) return "";
        String[] segments = rawSegments(source.getRawPath());
        return decode(segments[1]);
    }

    static URI create(String provider, String stableId) {
        if (!isKnownProvider(provider) || !isSafeIdentity(stableId)) return null;
        try {
            return URI.create(
                    SCHEME + "://" + HOST + "/"
                            + encode(provider.toLowerCase(Locale.ROOT)) + "/"
                            + encode(stableId)
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean validReferencePath(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/")) return false;
        String[] segments = rawSegments(rawPath);
        if (segments.length != 2) return false;
        String candidateProvider = decode(segments[0]);
        String candidateStableId = decode(segments[1]);
        return isKnownProvider(candidateProvider) && isSafeIdentity(candidateStableId);
    }

    private static String[] rawSegments(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/")) return new String[0];
        String value = rawPath.substring(1);
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.split("/", -1);
    }

    private static String highflySlug(URI source) {
        if (source == null || source.getPath() == null) return "";
        Matcher matcher = HIGHFLY_SLUG.matcher(source.getPath());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isHighflySlug(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{2,128}");
    }

    private static boolean isHighflyTvgId(String tvgId) {
        return "SkySportsF1.uk".equalsIgnoreCase(tvgId)
                || "ESPN.us".equalsIgnoreCase(tvgId)
                || "MarqueeSportsNetwork.us".equalsIgnoreCase(tvgId)
                || "SkySportsPremierLeague.uk".equalsIgnoreCase(tvgId)
                || "SkySport1.nz".equalsIgnoreCase(tvgId)
                || "SkySportsTennis.uk".equalsIgnoreCase(tvgId)
                || "SkySportsGolf.uk".equalsIgnoreCase(tvgId);
    }

    private static boolean isKnownProvider(String value) {
        return "tvn".equalsIgnoreCase(value)
                || "meganoticias".equalsIgnoreCase(value)
                || "tvvoo".equalsIgnoreCase(value)
                || "highfly".equalsIgnoreCase(value);
    }

    private static boolean isSafeIdentity(String value) {
        if (AppStrings.isBlank(value) || value.length() > 256) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f
                    || character == '/' || character == '\\'
                    || character == '?' || character == '#') return false;
        }
        return true;
    }

    private static String value(Map<String, String> attributes, String key) {
        if (attributes == null) return "";
        String value = attributes.get(key);
        return value == null ? "" : value.trim();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(
                    value.replace("+", "%2B"),
                    StandardCharsets.UTF_8.name()
            );
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }
}
