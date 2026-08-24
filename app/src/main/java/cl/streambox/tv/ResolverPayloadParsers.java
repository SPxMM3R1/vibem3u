package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing of resolver-owned HTML and JSON payloads. */
final class ResolverPayloadParsers {
    private static final int MAX_TVVOO_STREAMS = 16;
    private static final int MAX_MANIFEST_NODES = 4096;
    private static final int MAX_MANIFEST_DEPTH = 10;
    private static final Pattern ANCHOR_TAG_PATTERN = Pattern.compile(
            "<a\\b[^>]*>", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLASS_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\bclass\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DATA_MS_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\bdata-ms\\s*=\\s*[\"']([a-zA-Z0-9_-]+)[\"']", Pattern.CASE_INSENSITIVE
    );

    private ResolverPayloadParsers() {}

    static String parseTwentyFourHoursStreamId(
            String html,
            String configuredPattern,
            String fallbackId
    ) throws IOException {
        if (html == null || html.isBlank()) {
            throw new IOException("24 Horas no publicó su configuración.");
        }
        String id;
        if (configuredPattern == null || configuredPattern.isBlank()) {
            id = activeTwentyFourHoursId(html);
        } else {
            Pattern pattern = Pattern.compile(configuredPattern, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(html);
            id = matcher.find() && matcher.groupCount() >= 1
                    ? matcher.group(1).trim()
                    : null;
        }
        if (id == null || id.isBlank()) id = fallbackId;
        if (id == null || !id.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IOException("24 Horas no publicó un stream válido.");
        }
        return id;
    }

    private static String activeTwentyFourHoursId(String html) {
        Matcher tags = ANCHOR_TAG_PATTERN.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Matcher classes = CLASS_ATTRIBUTE_PATTERN.matcher(tag);
            if (!classes.find()) continue;
            String classValue = " " + classes.group(1).toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ").trim() + " ";
            if (!classValue.contains(" playertablink ")
                    || !classValue.contains(" active ")) continue;
            Matcher streamId = DATA_MS_ATTRIBUTE_PATTERN.matcher(tag);
            if (streamId.find()) return streamId.group(1).trim();
        }
        return null;
    }

    static List<URI> parseTvVooCandidates(String json) throws IOException {
        return parseTvVooCandidates(json, "streams", "url");
    }

    static List<URI> parseTvVooCandidates(
            String json,
            String streamsPath,
            String urlField
    ) throws IOException {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            JSONObject root = new JSONObject(json);
            Object streamsValue = jsonValueAtPath(root, streamsPath);
            JSONArray streams = streamsValue instanceof JSONArray
                    ? (JSONArray) streamsValue
                    : null;
            if (streams == null) return Collections.emptyList();
            LinkedHashSet<URI> result = new LinkedHashSet<>();
            for (int index = 0; index < streams.length()
                    && index < MAX_TVVOO_STREAMS; index++) {
                JSONObject stream = streams.optJSONObject(index);
                if (stream == null) continue;
                URI uri = httpUri(stream.optString(urlField, ""));
                if (uri != null) result.add(uri);
            }
            return Collections.unmodifiableList(new ArrayList<>(result));
        } catch (JSONException error) {
            throw new IOException("TvVoo devolvió JSON inválido.", error);
        }
    }

    static Object jsonValueAtPath(JSONObject root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        Object current = root;
        for (String field : path.split("\\.")) {
            if (!(current instanceof JSONObject) || field.isBlank()) return null;
            current = ((JSONObject) current).opt(field);
            if (current == null || current == JSONObject.NULL) return null;
        }
        return current;
    }

    static URI parseHighflyManifest(String json, List<String> identifiers)
            throws IOException {
        if (json == null || json.isBlank() || identifiers == null || identifiers.isEmpty()) {
            throw new IOException("El manifiesto Highfly está vacío.");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String identifier : identifiers) {
            String value = normalize(identifier);
            if (!value.isBlank()) normalized.add(value);
        }
        try {
            Object root = json.trim().startsWith("[")
                    ? new JSONArray(json)
                    : new JSONObject(json);
            SearchBudget budget = new SearchBudget();
            URI result = findManifestUri(root, normalized, 0, budget, false);
            if (result == null) throw new IOException("Canal ausente en el manifiesto Highfly.");
            return result;
        } catch (JSONException error) {
            throw new IOException("Highfly devolvió JSON inválido.", error);
        }
    }

    private static URI findManifestUri(
            Object node,
            Set<String> identifiers,
            int depth,
            SearchBudget budget,
            boolean parentMatched
    ) throws JSONException {
        if (node == null || node == JSONObject.NULL || depth > MAX_MANIFEST_DEPTH
                || ++budget.nodes > MAX_MANIFEST_NODES) return null;
        if (node instanceof String) {
            return parentMatched ? httpUri((String) node) : null;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                URI candidate = findManifestUri(
                        array.opt(index), identifiers, depth + 1, budget, parentMatched
                );
                if (candidate != null) return candidate;
            }
            return null;
        }
        if (!(node instanceof JSONObject)) return null;

        JSONObject object = (JSONObject) node;
        boolean matched = parentMatched || objectMatches(object, identifiers);
        if (matched) {
            for (String field : new String[]{"url", "hls", "stream", "streamUrl", "stream_url"}) {
                URI candidate = httpUri(object.optString(field, ""));
                if (candidate != null) return candidate;
            }
        }
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            String key = keys.next();
            boolean keyMatched = identifiers.contains(normalize(key));
            URI candidate = findManifestUri(
                    object.opt(key), identifiers, depth + 1, budget, matched || keyMatched
            );
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static boolean objectMatches(JSONObject object, Set<String> identifiers) {
        for (String field : new String[]{"id", "slug", "tvg-id", "tvgId", "name"}) {
            if (identifiers.contains(normalize(object.optString(field, "")))) return true;
        }
        return false;
    }

    private static URI httpUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) return null;
            String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.ROOT).contains(".m3u8")
                    ? uri
                    : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static final class SearchBudget {
        int nodes;
    }
}
