package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
    private static final int MAX_DISCOVERY_VALUE_LENGTH = 8192;
    private static final Pattern EMBEDDED_URL = Pattern.compile(
            "(?i)(https?://[^\\s\\\"'<>\\\\]+|(?:/|\\./|\\.\\./)"
                    + "[^\\s\\\"'<>\\\\]*?\\.m3u8(?:\\?[^\\s\\\"'<>\\\\]*)?)"
    );

    private ResolverPayloadParsers() {}

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

    /**
     * Finds HLS candidates in a bounded, data-only payload.
     *
     * <p>This is the APK counterpart of the tested Resolver Forge recipe. It
     * never evaluates JavaScript or downloads code. It only walks scalar JSON
     * values and applies reversible URL/Base64/JSON-string decoding under hard
     * depth, string and candidate budgets. Every returned URI still has to
     * pass the normal HLS playlist/variant/media-signature validator.</p>
     */
    static List<URI> parseBoundedHlsCandidates(
            String payload,
            URI baseUri,
            int maximumDepth,
            int maximumStrings,
            int maximumCandidates
    ) throws IOException {
        if (payload == null || payload.isBlank() || baseUri == null) {
            return Collections.emptyList();
        }
        int depthLimit = Math.max(1, Math.min(8, maximumDepth));
        int stringLimit = Math.max(8, Math.min(512, maximumStrings));
        int candidateLimit = Math.max(1, Math.min(32, maximumCandidates));
        Deque<DiscoveryValue> pending = new ArrayDeque<>();
        pending.add(new DiscoveryValue(payload, 0));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<URI> candidates = new LinkedHashSet<>();
        int processed = 0;

        while (!pending.isEmpty() && processed < stringLimit
                && candidates.size() < candidateLimit) {
            DiscoveryValue current = pending.removeFirst();
            String value = current.value == null ? "" : current.value.trim();
            if (value.isBlank() || value.length() > MAX_DISCOVERY_VALUE_LENGTH
                    || !seen.add(value)) {
                continue;
            }
            processed++;
            addEmbeddedHls(value, baseUri, candidates, candidateLimit);
            if (current.depth >= depthLimit) continue;

            String htmlDecoded = value
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'");
            enqueueDecoded(pending, value, htmlDecoded, current.depth + 1);
            try {
                enqueueDecoded(
                        pending,
                        value,
                        URLDecoder.decode(value, StandardCharsets.UTF_8.name()),
                        current.depth + 1
                );
            } catch (IllegalArgumentException ignored) {
                // Malformed percent encoding is untrusted input, not a fatal
                // parser error. Other values in the response can still work.
            }
            enqueueBase64(pending, value, current.depth + 1);
            enqueueJsonScalars(
                    pending,
                    value,
                    current.depth + 1,
                    depthLimit,
                    stringLimit - processed
            );
        }
        return Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    private static void addEmbeddedHls(
            String value,
            URI baseUri,
            LinkedHashSet<URI> result,
            int maximumCandidates
    ) {
        Matcher matcher = EMBEDDED_URL.matcher(value);
        while (matcher.find() && result.size() < maximumCandidates) {
            String raw = matcher.group(1).replace("\\/", "/")
                    .replaceAll("[),;\\]}]+$", "");
            try {
                URI candidate = baseUri.resolve(raw);
                URI accepted = httpUri(candidate.toString());
                if (accepted != null) result.add(accepted);
            } catch (IllegalArgumentException ignored) {
                // Ignore one malformed candidate and keep the bounded scan.
            }
        }
    }

    private static void enqueueDecoded(
            Deque<DiscoveryValue> pending,
            String original,
            String decoded,
            int depth
    ) {
        if (decoded != null && !decoded.isBlank() && !decoded.equals(original)
                && decoded.length() <= MAX_DISCOVERY_VALUE_LENGTH) {
            pending.addLast(new DiscoveryValue(decoded, depth));
        }
    }

    private static void enqueueBase64(
            Deque<DiscoveryValue> pending,
            String value,
            int depth
    ) {
        String compact = value.replaceAll("\\s+", "");
        if (compact.length() < 12 || compact.length() > MAX_DISCOVERY_VALUE_LENGTH) return;
        int remainder = compact.length() % 4;
        if (remainder == 1) return;
        int padding = (4 - remainder) % 4;
        String padded = compact + (padding == 0 ? "" : padding == 1 ? "=" : "==");
        byte[] decoded = decodeBase64(padded);
        if (decoded != null) {
            enqueueDecoded(
                    pending,
                    value,
                    new String(decoded, StandardCharsets.UTF_8),
                    depth
            );
        }
    }

    /** Standard and URL-safe Base64 decoder compatible with Android API 23. */
    private static byte[] decodeBase64(String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(value.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        boolean paddingStarted = false;
        int paddingCount = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '=') {
                paddingStarted = true;
                if (++paddingCount > 2) return null;
                continue;
            }
            if (paddingStarted) return null;
            int digit = base64Digit(character);
            if (digit < 0) return null;
            buffer = (buffer << 6) | digit;
            bits += 6;
            while (bits >= 8) {
                bits -= 8;
                output.write((buffer >> bits) & 0xFF);
                buffer = bits == 0 ? 0 : buffer & ((1 << bits) - 1);
            }
        }
        if (bits == 6 || (bits > 0 && buffer != 0)) return null;
        return output.toByteArray();
    }

    private static int base64Digit(char value) {
        if (value >= 'A' && value <= 'Z') return value - 'A';
        if (value >= 'a' && value <= 'z') return value - 'a' + 26;
        if (value >= '0' && value <= '9') return value - '0' + 52;
        if (value == '+' || value == '-') return 62;
        if (value == '/' || value == '_') return 63;
        return -1;
    }

    private static void enqueueJsonScalars(
            Deque<DiscoveryValue> pending,
            String value,
            int depth,
            int depthLimit,
            int remainingBudget
    ) {
        if (remainingBudget <= 0) return;
        String trimmed = value.trim();
        Object root;
        try {
            if (trimmed.startsWith("{")) root = new JSONObject(trimmed);
            else if (trimmed.startsWith("[")) root = new JSONArray(trimmed);
            else if (trimmed.startsWith("\"")) root = new JSONArray("[" + trimmed + "]");
            else return;
        } catch (JSONException error) {
            return;
        }
        collectJsonScalars(root, pending, depth, depthLimit, new SearchBudget(remainingBudget));
    }

    private static void collectJsonScalars(
            Object node,
            Deque<DiscoveryValue> pending,
            int depth,
            int depthLimit,
            SearchBudget budget
    ) {
        if (node == null || node == JSONObject.NULL || depth > depthLimit
                || budget.nodes++ >= budget.maximum) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                collectJsonScalars(
                        object.opt(keys.next()), pending, depth + 1, depthLimit, budget
                );
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                collectJsonScalars(array.opt(index), pending, depth + 1, depthLimit, budget);
            }
        } else {
            String scalar = String.valueOf(node).trim();
            if (!scalar.isBlank() && scalar.length() <= MAX_DISCOVERY_VALUE_LENGTH) {
                pending.addLast(new DiscoveryValue(scalar, depth));
            }
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
                || ++budget.nodes > budget.maximum) return null;
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
        final int maximum;

        SearchBudget() {
            this(MAX_MANIFEST_NODES);
        }

        SearchBudget(int maximum) {
            this.maximum = Math.max(1, maximum);
        }
    }

    private static final class DiscoveryValue {
        final String value;
        final int depth;

        DiscoveryValue(String value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }
}
