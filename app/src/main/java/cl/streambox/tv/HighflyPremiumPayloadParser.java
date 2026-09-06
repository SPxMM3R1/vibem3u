package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data-only parser for the Stremio payloads exposed by Highfly Premium.
 * No JavaScript is evaluated and no provider URL is used as an identity.
 */
final class HighflyPremiumPayloadParser {
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    static final int MAX_CATALOG_ENTRIES = 1_000;
    static final int MAX_STREAM_CANDIDATES = 24;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final Pattern STABLE_ID = Pattern.compile(
            "(?i)^leaf:([a-z0-9][a-z0-9_-]{1,127})$"
    );
    private static final Pattern EVENT_ID = Pattern.compile(
            "(?i)^(?:streamed|sf):[a-z0-9][a-z0-9:_-]{1,159}$"
    );
    private static final Pattern CATALOG_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_-]{0,79}"
    );
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(
            "(?i)(\\d{3,4})\\s*[x×]\\s*(\\d{3,4})"
    );
    private static final Pattern BITRATE_PATTERN = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(mbps|mbit/s|kbps|kbit/s)"
    );
    private static final Pattern PROGRESSIVE_RESOLUTION_PATTERN = Pattern.compile(
            "(?i)(?<!\\d)(\\d{3,4})\\s*[pi](?!\\w)"
    );
    private static final Pattern FPS_PATTERN = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:fps|frames?/s)"
    );

    private HighflyPremiumPayloadParser() {}

    static ManifestInfo parseManifest(String json) throws IOException {
        requireSize(json, MAX_MANIFEST_BYTES, "manifiesto");
        try {
            JSONObject root = new JSONObject(json);
            JSONArray resources = root.optJSONArray("resources");
            boolean hasCatalog = false;
            boolean hasMeta = false;
            boolean hasStream = false;
            if (resources != null) {
                for (int index = 0; index < resources.length(); index++) {
                    Object value = resources.opt(index);
                    String resource = value instanceof String
                            ? (String) value
                            : value instanceof JSONObject
                            ? ((JSONObject) value).optString("name", "")
                            : "";
                    if ("catalog".equalsIgnoreCase(resource)) hasCatalog = true;
                    if ("meta".equalsIgnoreCase(resource)
                            || "metadata".equalsIgnoreCase(resource)) hasMeta = true;
                    if ("stream".equalsIgnoreCase(resource)
                            || "streams".equalsIgnoreCase(resource)) hasStream = true;
                }
            }
            if (!hasCatalog || !hasStream) {
                throw new IOException("El manifiesto Premium no expone catálogo y streams.");
            }

            JSONArray catalogs = root.optJSONArray("catalogs");
            LinkedHashSet<String> catalogIds = new LinkedHashSet<>();
            if (catalogs != null) {
                for (int index = 0; index < catalogs.length(); index++) {
                    JSONObject catalog = catalogs.optJSONObject(index);
                    if (catalog == null
                            || !"sport".equalsIgnoreCase(catalog.optString("type", ""))) {
                        continue;
                    }
                    String id = catalog.optString("id", "").trim();
                    if (CATALOG_ID.matcher(id).matches()
                            && !id.toLowerCase(Locale.ROOT).contains("recap")) {
                        catalogIds.add(id);
                    }
                }
            }
            if (catalogIds.isEmpty()) {
                throw new IOException("El manifiesto Premium no expone catálogos deportivos.");
            }
            return new ManifestInfo(
                    new ArrayList<>(catalogIds),
                    hasMeta
            );
        } catch (JSONException error) {
            throw new IOException("El manifiesto Premium no es JSON válido.");
        }
    }

    static List<HighflyPremiumCatalog.Entry> parseCatalog(String json) throws IOException {
        requireSize(json, MAX_CATALOG_BYTES, "catálogo");
        try {
            JSONObject root = new JSONObject(json);
            JSONArray metas = root.optJSONArray("metas");
            if (metas == null) return Collections.emptyList();
            List<HighflyPremiumCatalog.Entry> entries = new ArrayList<>();
            Set<String> seenIds = new LinkedHashSet<>();
            for (int index = 0; index < metas.length()
                    && index < MAX_CATALOG_ENTRIES; index++) {
                JSONObject meta = metas.optJSONObject(index);
                if (meta == null) continue;
                String id = clean(meta.optString("id", ""), 180);
                HighflyPremiumCatalog.Entry entry = parseEntry(meta, id, index);
                if (entry != null && seenIds.add(entry.getId())) entries.add(entry);
            }
            return Collections.unmodifiableList(entries);
        } catch (JSONException error) {
            throw new IOException("El catálogo Premium no es JSON válido.");
        }
    }

    static List<StreamCandidate> parseStreams(String json) throws IOException {
        requireSize(json, MAX_CATALOG_BYTES, "streams");
        try {
            JSONObject root = new JSONObject(json);
            JSONArray streams = root.optJSONArray("streams");
            if (streams == null) return Collections.emptyList();
            Map<String, StreamCandidate> unique = new LinkedHashMap<>();
            for (int index = 0; index < streams.length()
                    && index < MAX_STREAM_CANDIDATES; index++) {
                JSONObject stream = streams.optJSONObject(index);
                if (stream == null) continue;
                URI uri = parseHttpsUri(firstNonBlank(
                        stream.optString("url", ""),
                        stream.optString("hls", ""),
                        stream.optString("stream", ""),
                        stream.optString("streamUrl", ""),
                        stream.optString("stream_url", "")
                ));
                if (uri == null) continue;
                String name = clean(firstNonBlank(
                        stream.optString("name", ""),
                        stream.optString("title", ""),
                        "Fuente Premium"
                ), MAX_TEXT_LENGTH);
                String title = clean(stream.optString("title", ""), MAX_TEXT_LENGTH);
                String declaredWidth = stream.optString("width", "");
                String declaredHeight = stream.optString("height", "");
                String metadata = name + " " + title + " "
                        + stream.optString("quality", "") + " "
                        + stream.optString("resolution", "") + " "
                        + stream.optString("bitrate", "") + " "
                        + stream.optString("bandwidth", "") + " "
                        + declaredWidth + "x" + declaredHeight + " "
                        + stream.optString("fps", "");
                StreamCandidate candidate = new StreamCandidate(
                        uri,
                        name,
                        title,
                        qualityScore(metadata)
                );
                if (!unique.containsKey(uri.toString())) {
                    unique.put(uri.toString(), candidate);
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(unique.values()));
        } catch (JSONException error) {
            throw new IOException("La respuesta de streams Premium no es JSON válida.");
        }
    }

    private static HighflyPremiumCatalog.Entry parseEntry(
            JSONObject meta,
            String id,
            int index
    ) {
        Matcher stable = STABLE_ID.matcher(id);
        HighflyPremiumCatalog.EntryType type;
        String slug = "";
        if (stable.matches()) {
            type = HighflyPremiumCatalog.EntryType.STABLE_CHANNEL;
            slug = stable.group(1);
        } else if (EVENT_ID.matcher(id).matches()) {
            type = HighflyPremiumCatalog.EntryType.TEMPORARY_EVENT;
        } else {
            // Keep an explicit, harmless marker for diagnostics/preview, but
            // never retain an unsafe provider ID or turn it into a Channel.
            return new HighflyPremiumCatalog.Entry(
                    "unsupported-" + index,
                    "",
                    "Entrada Premium no compatible",
                    "",
                    null,
                    "",
                    "",
                    HighflyPremiumCatalog.EntryType.UNSUPPORTED
            );
        }

        String name = clean(meta.optString("name", ""), 180);
        if (AppStrings.isBlank(name)) name = AppStrings.isBlank(slug) ? id : slug;
        String category = firstGenre(meta.optJSONArray("genres"));
        URI logo = parseImageUri(firstNonBlank(
                meta.optString("poster", ""),
                meta.optString("background", "")
        ));
        return new HighflyPremiumCatalog.Entry(
                id,
                slug,
                name,
                category,
                logo,
                clean(meta.optString("description", ""), MAX_TEXT_LENGTH),
                clean(meta.optString("releaseInfo", ""), 80),
                type
        );
    }

    private static String firstGenre(JSONArray genres) {
        if (genres == null) return "";
        for (int index = 0; index < genres.length(); index++) {
            String genre = clean(genres.optString(index, ""), 80);
            if (!AppStrings.isBlank(genre)) return genre;
        }
        return "";
    }

    private static URI parseImageUri(String value) {
        if (value == null || AppStrings.isBlank(value)) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"cdn.highfly.dev".equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || uri.getPath() == null
                    || uri.getPath().contains("..")) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static URI parseHttpsUri(String value) {
        if (value == null || AppStrings.isBlank(value)) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.toString().length() > 4096) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static long qualityScore(String value) {
        int height = 0;
        int width = 0;
        Matcher resolution = RESOLUTION_PATTERN.matcher(value == null ? "" : value);
        if (resolution.find()) {
            width = parseInt(resolution.group(1));
            height = parseInt(resolution.group(2));
        }
        Matcher progressive = PROGRESSIVE_RESOLUTION_PATTERN.matcher(
                value == null ? "" : value
        );
        while (progressive.find()) {
            int candidateHeight = parseInt(progressive.group(1));
            if (candidateHeight > height) height = candidateHeight;
        }
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (normalized.contains("8k")) height = Math.max(height, 4320);
        else if (normalized.contains("4k") || normalized.contains("uhd")) {
            height = Math.max(height, 2160);
        } else if (normalized.contains("fhd") || normalized.contains("full hd")) {
            height = Math.max(height, 1080);
        } else if (normalized.matches(".*\\bhd\\b.*")) {
            height = Math.max(height, 720);
        }

        if (width == 0 && height > 0) {
            width = Math.round(height * 16f / 9f);
        }

        long bitrate = 0L;
        Matcher bitrateMatcher = BITRATE_PATTERN.matcher(value == null ? "" : value);
        if (bitrateMatcher.find()) {
            try {
                double amount = Double.parseDouble(
                        bitrateMatcher.group(1).replace(',', '.')
                );
                String unit = bitrateMatcher.group(2).toLowerCase(Locale.ROOT);
                bitrate = Math.round(amount * (unit.startsWith("m") ? 1_000_000d : 1_000d));
            } catch (NumberFormatException ignored) {
                bitrate = 0;
            }
        }
        Matcher fpsMatcher = FPS_PATTERN.matcher(value == null ? "" : value);
        long fps = 0L;
        if (fpsMatcher.find()) {
            try {
                fps = Math.round(Double.parseDouble(
                        fpsMatcher.group(1).replace(',', '.')
                ));
            } catch (NumberFormatException ignored) {
                fps = 0L;
            }
        }
        return (long) height * 1_000_000_000L
                + (long) width * 1_000_000L
                + Math.max(0L, bitrate)
                + fps;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !AppStrings.isBlank(value)) return value.trim();
        }
        return "";
    }

    private static String clean(String value, int maximumLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maximumLength) return normalized;
        return normalized.substring(0, maximumLength).trim();
    }

    private static void requireSize(String value, int maximumBytes, String label)
            throws IOException {
        if (value == null || AppStrings.isBlank(value)) throw new IOException("Respuesta " + label + " vacía.");
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IOException("Respuesta " + label + " demasiado grande.");
        }
    }

    static final class ManifestInfo {
        private final List<String> catalogIds;
        private final boolean hasMetadata;

        ManifestInfo(List<String> catalogIds, boolean hasMetadata) {
            this.catalogIds = Collections.unmodifiableList(new ArrayList<>(catalogIds));
            this.hasMetadata = hasMetadata;
        }

        List<String> getCatalogIds() {
            return catalogIds;
        }

        boolean hasMetadata() {
            return hasMetadata;
        }
    }

    static final class StreamCandidate {
        private final URI uri;
        private final String name;
        private final String title;
        private final long qualityScore;

        StreamCandidate(URI uri, String name, String title, long qualityScore) {
            this.uri = uri;
            this.name = name;
            this.title = title;
            this.qualityScore = qualityScore;
        }

        URI getUri() {
            return uri;
        }

        String getName() {
            return name;
        }

        String getTitle() {
            return title;
        }

        long getQualityScore() {
            return qualityScore;
        }
    }
}
