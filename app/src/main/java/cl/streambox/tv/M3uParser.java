package cl.streambox.tv;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class M3uParser {
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*\\\"([^\\\"]*)\\\"");

    private M3uParser() {}

    public static List<Channel> parse(String content, URI playlistUri) {
        return parsePlaylist(content, playlistUri).getChannels();
    }

    public static Playlist parsePlaylist(String content, URI playlistUri) {
        List<Channel> channels = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            return new Playlist(channels, null);
        }

        String pendingName = null;
        String pendingGroup = "";
        URI pendingLogo = null;
        Map<String, String> pendingAttributes = new LinkedHashMap<>();

        String normalized = content.replace("\uFEFF", "");
        for (String rawLine : normalized.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                pendingAttributes = parseAttributes(line);
                pendingName = parseDisplayName(line);
                pendingGroup = valueOrDefault(pendingAttributes, "group-title", "");
                pendingLogo = resolveUri(playlistUri, pendingAttributes.get("tvg-logo"));
                continue;
            }

            if (line.startsWith("#")) {
                if (pendingName != null || !pendingAttributes.isEmpty()) {
                    ChannelRequestHeaders.parseOption(line, pendingAttributes);
                }
                continue;
            }

            URI streamUri = resolveUri(playlistUri, line);
            if (streamUri == null || streamUri.getScheme() == null) {
                resetPending(pendingAttributes);
                pendingName = null;
                pendingLogo = null;
                pendingGroup = "";
                continue;
            }

            if ("tvvoo".equalsIgnoreCase(streamUri.getScheme())
                    && !normalizeTvVooEntry(streamUri, pendingAttributes)) {
                // A TvVoo reference without a stable identity and alias is
                // unsafe to hand to Media3. Drop the row instead of allowing
                // a placeholder or an old session URL to play directly.
                resetPending(pendingAttributes);
                pendingName = null;
                pendingLogo = null;
                pendingGroup = "";
                continue;
            }

            if (DynamicSourceReference.isInternalScheme(streamUri)) {
                if (!DynamicSourceReference.isAppOnly(streamUri)) {
                    resetPending(pendingAttributes);
                    pendingName = null;
                    pendingLogo = null;
                    pendingGroup = "";
                    continue;
                }
            } else {
                URI appOnlyReference = DynamicSourceReference.normalize(
                        streamUri,
                        pendingAttributes
                );
                if (appOnlyReference != null) {
                    DynamicSourceReference.enrichAttributes(
                            streamUri,
                            pendingAttributes
                    );
                    streamUri = appOnlyReference;
                }
            }

            String name = pendingName;
            if (name == null || AppStrings.isBlank(name)) {
                name = valueOrDefault(pendingAttributes, "tvg-name", "Canal " + (channels.size() + 1));
            }
            channels.add(new Channel(name.trim(), streamUri, pendingLogo, pendingGroup, pendingAttributes));
            pendingName = null;
            pendingLogo = null;
            pendingGroup = "";
            pendingAttributes = new LinkedHashMap<>();
        }
        return Playlist.withEpgUris(channels, parseEpgUris(normalized, playlistUri));
    }

    private static List<URI> parseEpgUris(String content, URI playlistUri) {
        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (!line.regionMatches(true, 0, "#EXTM3U", 0, 7)) {
                return Collections.emptyList();
            }

            Map<String, String> attributes = parseAttributes(line);
            String value = attributes.get("x-tvg-url");
            if (value == null || AppStrings.isBlank(value)) value = attributes.get("url-tvg");
            if (value == null || AppStrings.isBlank(value)) return Collections.emptyList();
            List<URI> epgUris = new ArrayList<>();
            for (String candidate : value.split(",")) {
                URI epgUri = resolveUri(playlistUri, candidate);
                if (epgUri != null && !epgUris.contains(epgUri)) {
                    epgUris.add(epgUri);
                }
            }
            return epgUris;
        }
        return Collections.emptyList();
    }

    private static Map<String, String> parseAttributes(String extInf) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(extInf);
        while (matcher.find()) {
            attributes.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2).trim());
        }
        return attributes;
    }

    private static String parseDisplayName(String extInf) {
        boolean quoted = false;
        for (int i = 8; i < extInf.length(); i++) {
            char c = extInf.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                return extInf.substring(i + 1).trim();
            }
        }
        return null;
    }

    private static URI resolveUri(URI baseUri, String value) {
        if (value == null || AppStrings.isBlank(value)) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("://") || containsWhitespace(candidate)) {
            return null;
        }
        try {
            URI uri = URI.create(candidate);
            return uri.isAbsolute() || baseUri == null ? uri : baseUri.resolve(uri);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void resetPending(Map<String, String> attributes) {
        attributes.clear();
    }

    private static String valueOrDefault(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Normalizes the tokenless TvVoo M3U contract in-place. The URI carries
     * only the stable row identity; all resolver aliases remain explicit
     * metadata so the resolver can renew the actual stream later.
     */
    private static boolean normalizeTvVooEntry(
            URI streamUri,
            Map<String, String> attributes
    ) {
        if (streamUri == null || attributes == null
                || !"channel".equalsIgnoreCase(streamUri.getHost())
                || streamUri.getUserInfo() != null
                || streamUri.getPort() != -1
                || streamUri.getRawQuery() != null
                || streamUri.getRawFragment() != null) return false;
        String rawPath = streamUri.getRawPath();
        if (rawPath == null || !rawPath.startsWith("/")
                || rawPath.length() <= 1 || rawPath.indexOf('/', 1) >= 0) return false;

        String stableId = decodeOnce(rawPath.substring(1));
        if (AppStrings.isBlank(stableId) || stableId.length() > 256
                || hasUnsafeIdentityCharacters(stableId)
                || !TvVooCatalogChannel.isStableId(stableId)) return false;
        String declaredTvgId = attributes.get("tvg-id");
        if (!AppStrings.isBlank(declaredTvgId)
                && !declaredTvgId.equals(stableId + "@TvVoo")) return false;
        String explicitId = attributes.get("x-resolver-id");
        if (AppStrings.isBlank(explicitId)) {
            attributes.put("x-resolver-id", stableId);
        }
        if (AppStrings.isBlank(attributes.get("tvg-id"))) {
            attributes.put("tvg-id", stableId + "@TvVoo");
        }
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-stable-id", stableId);
        attributes.put("x-resolver-country", stableId.substring(0, stableId.indexOf('|')));

        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        addAliases(attributes.get("x-resolver-ids"), aliases);
        addAliases(attributes.get("x-tvvoo-alias"), aliases);
        addAliasFromIdentity(explicitId, aliases);
        addAliasFromIdentity(stableId, aliases);
        if (aliases.isEmpty()) return false;

        StringBuilder serialized = new StringBuilder();
        for (String alias : aliases) {
            if (serialized.length() > 0) serialized.append(';');
            serialized.append(alias);
        }
        attributes.put("x-resolver-ids", serialized.toString());
        return true;
    }

    private static void addAliases(String value, LinkedHashSet<String> result) {
        if (AppStrings.isBlank(value)) return;
        for (String candidate : value.split(";")) {
            String canonical = TvVooSourceHistory.canonicalAlias(candidate);
            if (!canonical.isEmpty()) result.add(canonical);
        }
    }

    private static void addAliasFromIdentity(String identity, LinkedHashSet<String> result) {
        if (AppStrings.isBlank(identity)) return;
        String value = identity.trim();
        String direct = TvVooSourceHistory.canonicalAlias(value);
        if (!direct.isEmpty()) {
            result.add(direct);
            return;
        }
        // Catalog stable ids commonly use country|alias. Only the exact
        // alias component is considered; display names never participate.
        String decoded = decodeOnce(value);
        int aliasStart = decoded.indexOf("vavoo_");
        if (aliasStart >= 0) {
            String canonical = TvVooSourceHistory.canonicalAlias(
                    decoded.substring(aliasStart)
            );
            if (!canonical.isEmpty()) result.add(canonical);
        }
    }

    private static String decodeOnce(String value) {
        try {
            return URLDecoder.decode(
                    value.replace("+", "%2B"),
                    java.nio.charset.StandardCharsets.UTF_8.name()
            );
        } catch (Exception ignored) {
            return value;
        }
    }

    private static boolean hasUnsafeIdentityCharacters(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f
                    || character == '/' || character == '\\'
                    || character == '?' || character == '#') return true;
        }
        return false;
    }
}
