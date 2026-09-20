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
import java.util.Comparator;
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
    private static final int MAX_HIGHFLY_STREAMS = 32;
    private static final int MAX_HIGHFLY_PAYLOAD_BYTES = 512 * 1024;
    private static final int MAX_HIGHFLY_METADATA_LENGTH = 160;
    private static final Pattern EMBEDDED_URL = Pattern.compile(
            "(?i)(https?://[^\\s\\\"'<>\\\\]+|(?:/|\\./|\\.\\./)"
                    + "[^\\s\\\"'<>\\\\]*?\\.m3u8(?:\\?[^\\s\\\"'<>\\\\]*)?)"
    );
    private static final Pattern BITRATE = Pattern.compile(
            "(?i)(\\d+(?:[\\.,]\\d+)?)\\s*"
                    + "(gbps|gbit/s|gbit|mbps|mb/s|mbit/s|mbit|"
                    + "kbps|kb/s|kbit/s|kbit|bps|bit/s|bit)"
    );
    private static final Pattern DIMENSIONS = Pattern.compile(
            "(?<!\\d)(\\d{3,5})\\s*[xX×]\\s*(\\d{3,5})(?!\\d)"
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
        if (json == null || AppStrings.isBlank(json)) return Collections.emptyList();
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
        if (payload == null || AppStrings.isBlank(payload) || baseUri == null) {
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
            if (AppStrings.isBlank(value) || value.length() > MAX_DISCOVERY_VALUE_LENGTH
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
            // A percent-encoded query delimiter means this is still an
            // envelope value, not the final HLS URL. The bounded queue will
            // URL-decode it and add only the usable canonical candidate.
            if (raw.toLowerCase(Locale.ROOT).contains("%3f")) continue;
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
        if (decoded != null && !AppStrings.isBlank(decoded) && !decoded.equals(original)
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

    /** Standard and URL-safe Base64 decoder compatible with the Android TV API 29 floor. */
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
            if (!AppStrings.isBlank(scalar) && scalar.length() <= MAX_DISCOVERY_VALUE_LENGTH) {
                pending.addLast(new DiscoveryValue(scalar, depth));
            }
        }
    }

    static Object jsonValueAtPath(JSONObject root, String path) {
        if (root == null || path == null || AppStrings.isBlank(path)) return null;
        Object current = root;
        for (String field : path.split("\\.")) {
            if (!(current instanceof JSONObject) || AppStrings.isBlank(field)) return null;
            current = ((JSONObject) current).opt(field);
            if (current == null || current == JSONObject.NULL) return null;
        }
        return current;
    }

    /**
     * Parses the public Highfly stream-api response without retaining any
     * provider URL outside the current resolution attempt.
     *
     * <p>The endpoint follows the Stremio-style {@code streams} envelope, but
     * the advertised quality can be exposed either in numeric fields or in the
     * human-readable title/name. The parser accepts both forms, applies hard
     * stream/payload limits, and keeps the API order for equal or unknown
     * bitrates.</p>
     */
    static List<HighflyCandidate> parseHighflyCandidates(
            String json,
            String streamsPath,
            int maximumStreams
    ) throws IOException {
        if (json == null || AppStrings.isBlank(json)) return Collections.emptyList();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_HIGHFLY_PAYLOAD_BYTES) {
            throw new IOException("Respuesta Highfly demasiado grande.");
        }
        int streamLimit = Math.max(1, Math.min(MAX_HIGHFLY_STREAMS, maximumStreams));
        try {
            String trimmed = json.trim();
            Object root = trimmed.startsWith("[")
                    ? new JSONArray(trimmed)
                    : new JSONObject(trimmed);
            Object streamsValue = root instanceof JSONArray
                    ? root
                    : jsonValueAtPath((JSONObject) root, streamsPath);
            if (!(streamsValue instanceof JSONArray)) return Collections.emptyList();

            JSONArray streams = (JSONArray) streamsValue;
            List<HighflyCandidate> candidates = new ArrayList<>();
            LinkedHashSet<String> seenUris = new LinkedHashSet<>();
            for (int index = 0; index < streams.length() && index < streamLimit; index++) {
                Object raw = streams.opt(index);
                HighflyCandidate candidate = raw instanceof JSONObject
                        ? parseHighflyCandidate((JSONObject) raw, index)
                        : parseHighflyStringCandidate(raw, index);
                if (candidate == null || !seenUris.add(candidate.uri.toString())) continue;
                candidates.add(candidate);
            }
            candidates.sort(new Comparator<HighflyCandidate>() {
                @Override
                public int compare(HighflyCandidate left, HighflyCandidate right) {
                    int bitrate = Long.compare(
                            right.advertisedBitrateBitsPerSecond,
                            left.advertisedBitrateBitsPerSecond
                    );
                    return bitrate != 0
                            ? bitrate
                            : Integer.compare(left.order, right.order);
                }
            });
            return Collections.unmodifiableList(candidates);
        } catch (JSONException error) {
            throw new IOException("Highfly devolvió JSON inválido.", error);
        }
    }

    private static HighflyCandidate parseHighflyCandidate(JSONObject object, int order) {
        URI uri = firstHlsUri(object);
        if (uri == null) return null;
        CandidateMetadata metadata = new CandidateMetadata();
        collectHighflyMetadata(object, metadata, 0);
        return new HighflyCandidate(
                uri,
                metadata.bitrateBitsPerSecond,
                metadata.width,
                metadata.height,
                metadata.name,
                metadata.title,
                order
        );
    }

    private static HighflyCandidate parseHighflyStringCandidate(Object raw, int order) {
        if (!(raw instanceof String)) return null;
        URI uri = httpUri((String) raw);
        return uri == null
                ? null
                : new HighflyCandidate(uri, 0L, 0, 0, "", "", order);
    }

    private static URI firstHlsUri(JSONObject object) {
        return firstHlsUri(object, 0);
    }

    private static URI firstHlsUri(JSONObject object, int depth) {
        if (depth > 3) return null;
        if (object == null) return null;
        for (String field : new String[]{
                "url", "hls", "streamUrl", "stream_url", "stream"
        }) {
            URI direct = hlsUri(object.opt(field), depth);
            if (direct != null) return direct;
        }
        for (String field : new String[]{"source", "streamInfo", "playback"}) {
            Object nested = object.opt(field);
            if (nested instanceof JSONObject) {
                URI value = firstHlsUri((JSONObject) nested, depth + 1);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static URI hlsUri(Object value, int depth) {
        if (value instanceof String) return httpUri((String) value);
        if (value instanceof JSONObject) return firstHlsUri((JSONObject) value, depth + 1);
        return null;
    }

    private static void collectHighflyMetadata(
            JSONObject object,
            CandidateMetadata metadata,
            int depth
    ) {
        if (object == null || metadata == null || depth > 2) return;
        for (String field : new String[]{
                "bitrate", "bitrateBps", "bitrateKbps", "bandwidth", "bandwidthBps",
                "bandwidthKbps", "kbps", "mbps"
        }) {
            metadata.bitrateBitsPerSecond = Math.max(
                    metadata.bitrateBitsPerSecond,
                    parseBitrate(object.opt(field), field)
            );
        }
        for (String field : new String[]{"width", "videoWidth"}) {
            metadata.width = Math.max(metadata.width, positiveInt(object.opt(field)));
        }
        for (String field : new String[]{"height", "videoHeight"}) {
            metadata.height = Math.max(metadata.height, positiveInt(object.opt(field)));
        }
        for (String field : new String[]{"name", "title", "label", "description", "quality"}) {
            Object value = object.opt(field);
            if (!(value instanceof String)) continue;
            String text = ((String) value).trim();
            if (AppStrings.isBlank(text)) continue;
            if (text.length() > MAX_HIGHFLY_METADATA_LENGTH) {
                text = text.substring(0, MAX_HIGHFLY_METADATA_LENGTH);
            }
            if ("name".equals(field) && AppStrings.isBlank(metadata.name)) {
                metadata.name = text;
            }
            if ("title".equals(field) && AppStrings.isBlank(metadata.title)) {
                metadata.title = text;
            }
            metadata.bitrateBitsPerSecond = Math.max(
                    metadata.bitrateBitsPerSecond,
                    parseBitrate(text, field)
            );
            Matcher dimensions = DIMENSIONS.matcher(text);
            if (dimensions.find()) {
                metadata.width = Math.max(metadata.width, parseInt(dimensions.group(1)));
                metadata.height = Math.max(metadata.height, parseInt(dimensions.group(2)));
            }
        }
        for (String field : new String[]{"quality", "video", "streamInfo", "metadata"}) {
            Object nested = object.opt(field);
            if (nested instanceof JSONObject) {
                collectHighflyMetadata((JSONObject) nested, metadata, depth + 1);
            }
        }
    }

    private static long parseBitrate(Object value, String field) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) {
            return normalizeNumericBitrate(((Number) value).doubleValue(), field);
        }
        if (!(value instanceof String)) return 0L;
        String text = ((String) value).trim();
        Matcher matcher = BITRATE.matcher(text);
        if (matcher.find()) {
            double number = parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            double multiplier = unit.startsWith("g")
                    ? 1_000_000_000d
                    : unit.startsWith("m")
                    ? 1_000_000d
                    : unit.startsWith("k")
                    ? 1_000d
                    : 1d;
            return boundedBitrate(number * multiplier);
        }
        try {
            return normalizeNumericBitrate(Double.parseDouble(text), field);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long normalizeNumericBitrate(double value, String field) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0d) return 0L;
        String lower = field == null ? "" : field.toLowerCase(Locale.ROOT);
        double multiplier = lower.contains("gb")
                ? 1_000_000_000d
                : lower.contains("mb")
                ? 1_000_000d
                : lower.contains("kb")
                ? 1_000d
                : (lower.contains("bitrate") && value < 100_000d)
                ? 1_000d
                : 1d;
        return boundedBitrate(value * multiplier);
    }

    private static long boundedBitrate(double value) {
        if (value <= 0d || Double.isNaN(value) || Double.isInfinite(value)) return 0L;
        return Math.min(100_000_000_000L, Math.round(value));
    }

    private static int positiveInt(Object value) {
        if (value instanceof Number) return Math.max(0, ((Number) value).intValue());
        if (value instanceof String) return parseInt((String) value);
        return 0;
    }

    private static int parseInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (Exception ignored) {
            return 0d;
        }
    }

    static final class HighflyCandidate {
        private final URI uri;
        private final long advertisedBitrateBitsPerSecond;
        private final int width;
        private final int height;
        private final String name;
        private final String title;
        private final int order;

        HighflyCandidate(
                URI uri,
                long advertisedBitrateBitsPerSecond,
                int width,
                int height,
                String name,
                String title,
                int order
        ) {
            this.uri = uri;
            this.advertisedBitrateBitsPerSecond = advertisedBitrateBitsPerSecond;
            this.width = width;
            this.height = height;
            this.name = name == null ? "" : name;
            this.title = title == null ? "" : title;
            this.order = order;
        }

        URI getUri() { return uri; }
        long getAdvertisedBitrateBitsPerSecond() { return advertisedBitrateBitsPerSecond; }
        int getWidth() { return width; }
        int getHeight() { return height; }

        String displayLabel() {
            if (!AppStrings.isBlank(title)) return title;
            if (!AppStrings.isBlank(name)) return name;
            return "Fuente Highfly";
        }

        String displayDetail() {
            StringBuilder detail = new StringBuilder();
            if (width > 0 && height > 0) {
                detail.append(width).append('x').append(height);
            }
            if (advertisedBitrateBitsPerSecond > 0L) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append(String.format(
                        Locale.ROOT,
                        "~%.1f Mbps",
                        advertisedBitrateBitsPerSecond / 1_000_000d
                ));
            }
            return detail.toString();
        }
    }

    static final class HighflyCatalogEntry {
        private final String id;
        private final String name;

        HighflyCatalogEntry(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        String getId() { return id; }
        String getName() { return name; }
    }

    private static final class CandidateMetadata {
        long bitrateBitsPerSecond;
        int width;
        int height;
        String name = "";
        String title = "";
    }

    /**
     * Checks the public Highfly/Stremio manifest without treating it as a
     * stream catalogue. The manifest describes the resource routes; HLS
     * candidates are obtained from the stream resource for the channel.
     */
    static boolean isHighflyStreamManifest(String json) throws IOException {
        if (json == null || AppStrings.isBlank(json)) return false;
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_HIGHFLY_PAYLOAD_BYTES) {
            throw new IOException("Manifiesto Highfly demasiado grande.");
        }
        try {
            JSONObject root = new JSONObject(json);
            Object resourcesValue = root.opt("resources");
            if (!(resourcesValue instanceof JSONArray)) return false;
            JSONArray resources = (JSONArray) resourcesValue;
            for (int index = 0; index < resources.length(); index++) {
                JSONObject resource = resources.optJSONObject(index);
                if (resource == null) continue;
                if ("stream".equalsIgnoreCase(resource.optString("name", ""))) {
                    return true;
                }
            }
            return false;
        } catch (JSONException error) {
            throw new IOException("Highfly devolvió un manifiesto inválido.", error);
        }
    }

    /** Returns live catalog entries whose id or display name matches a channel. */
    static List<HighflyCatalogEntry> parseHighflyCatalog(
            String json,
            List<String> identifiers,
            int maximumEntries
    ) throws IOException {
        if (json == null || AppStrings.isBlank(json)
                || identifiers == null || identifiers.isEmpty()) {
            return Collections.emptyList();
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_HIGHFLY_PAYLOAD_BYTES) {
            throw new IOException("Catálogo Highfly demasiado grande.");
        }
        LinkedHashSet<String> normalizedIdentifiers = new LinkedHashSet<>();
        for (String identifier : identifiers) {
            String normalized = normalize(identifier);
            if (!AppStrings.isBlank(normalized)) normalizedIdentifiers.add(normalized);
            String display = normalizeHighflyName(identifier);
            if (!AppStrings.isBlank(display)) normalizedIdentifiers.add(display);
        }
        int limit = Math.max(1, Math.min(16, maximumEntries));
        try {
            JSONObject root = new JSONObject(json);
            Object metasValue = root.opt("metas");
            if (!(metasValue instanceof JSONArray)) return Collections.emptyList();
            JSONArray metas = (JSONArray) metasValue;
            List<HighflyCatalogEntry> result = new ArrayList<>();
            LinkedHashSet<String> seenIds = new LinkedHashSet<>();
            for (int index = 0; index < metas.length() && result.size() < limit; index++) {
                JSONObject meta = metas.optJSONObject(index);
                if (meta == null) continue;
                String id = meta.optString("id", "").trim();
                if (!id.matches("[A-Za-z0-9_-]+:[A-Za-z0-9_-]{2,128}")) continue;
                String name = meta.optString("name", "").trim();
                if (!matchesHighflyCatalogEntry(id, name, normalizedIdentifiers)) continue;
                if (seenIds.add(id)) result.add(new HighflyCatalogEntry(id, name));
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException error) {
            throw new IOException("Highfly devolvió un catálogo inválido.", error);
        }
    }

    private static boolean matchesHighflyCatalogEntry(
            String id,
            String name,
            Set<String> identifiers
    ) {
        String normalizedId = normalize(id);
        String normalizedResourceId = id.indexOf(':') >= 0
                ? normalize(id.substring(id.indexOf(':') + 1))
                : normalizedId;
        String normalizedName = normalizeHighflyName(name);
        for (String identifier : identifiers) {
            if (identifier.equals(normalizedId)
                    || identifier.equals(normalizedResourceId)) return true;
            if (!AppStrings.isBlank(normalizedName)
                    && (normalizedName.contains(identifier) || identifier.contains(normalizedName))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHighflyName(String value) {
        if (value == null) return "";
        String withoutQuality = value.toLowerCase(Locale.ROOT)
                .replaceAll("fullhd|ultrahd|uhd|fhd|4k|2k|1080p|720p|576p|480p|hd|sd|leaf", "");
        return normalize(withoutQuality);
    }

    static URI parseHighflyManifest(String json, List<String> identifiers)
            throws IOException {
        if (json == null || AppStrings.isBlank(json) || identifiers == null || identifiers.isEmpty()) {
            throw new IOException("El manifiesto Highfly está vacío.");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String identifier : identifiers) {
            String value = normalize(identifier);
            if (!AppStrings.isBlank(value)) normalized.add(value);
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
        if (value == null || AppStrings.isBlank(value)) return null;
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
