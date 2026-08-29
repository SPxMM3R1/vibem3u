package cl.streambox.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

            String name = pendingName;
            if (name == null || name.isBlank()) {
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
            if (value == null || value.isBlank()) value = attributes.get("url-tvg");
            if (value == null || value.isBlank()) return Collections.emptyList();
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
        if (value == null || value.isBlank()) {
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
}
