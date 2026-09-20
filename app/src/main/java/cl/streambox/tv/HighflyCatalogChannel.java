package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.Normalizer;

/** Stable metadata for one Highfly catalogue entry.
 *
 * <p>The resource id and stable id identify the entry inside the app. No
 * stream URL, session parameter or authorization value is persisted here; the
 * resolver obtains the playable HLS source when the channel is opened. The
 * app-to-GitHub selection manifest intentionally keeps this provider identity
 * separate from the public XMLTV/tvg-id assigned by Lista M3U.</p>
 */
public final class HighflyCatalogChannel {
    private final String resourceId;
    private final String slug;
    private final String stableId;
    private final String name;
    private final String group;
    private final String category;
    private final String logoUrl;
    private final List<String> genres;

    public HighflyCatalogChannel(
            String resourceId,
            String name,
            String group,
            String category,
            String logoUrl,
            List<String> genres
    ) {
        this(
                resourceId,
                "",
                name,
                group,
                category,
                logoUrl,
                genres
        );
    }

    /**
     * Creates a catalogue entry with an identity independent of the current
     * Highfly leaf slug. The overload without {@code stableId} remains for
     * old persisted rows and derives a deterministic name-based identity.
     */
    public HighflyCatalogChannel(
            String resourceId,
            String stableId,
            String name,
            String group,
            String category,
            String logoUrl,
            List<String> genres
    ) {
        String normalizedResourceId = clean(resourceId).toLowerCase(Locale.ROOT);
        if (!normalizedResourceId.matches("leaf:[A-Za-z0-9_-]{2,128}")) {
            throw new IllegalArgumentException("ID Highfly inválido.");
        }
        this.resourceId = normalizedResourceId;
        this.slug = normalizedResourceId.substring("leaf:".length());
        this.name = nonBlank(name, this.slug);
        this.stableId = stableIdentity(stableId, this.name);
        this.group = nonBlank(group, "Highfly");
        this.category = clean(category);
        this.logoUrl = safeLogoUrl(logoUrl);

        LinkedHashSet<String> genreValues = new LinkedHashSet<>();
        if (genres != null) {
            for (String value : genres) {
                String candidate = clean(value);
                if (!candidate.isEmpty()) genreValues.add(candidate);
            }
        }
        if (genreValues.isEmpty() && !this.category.isEmpty()) {
            genreValues.add(this.category);
        }
        this.genres = Collections.unmodifiableList(new ArrayList<>(genreValues));
    }

    public String getResourceId() { return resourceId; }
    public String getSlug() { return slug; }
    public String getStableId() { return stableId; }
    public String getName() { return name; }
    public String getGroup() { return group; }
    public String getCategory() { return category; }
    public String getLogoUrl() { return logoUrl; }
    public List<String> getGenres() { return genres; }

    /** Creates the tokenless app-only reference consumed by HighflyResolver. */
    public Channel toChannel() {
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("tvg-id", tvgId());
        attributes.put("tvg-name", name);
        if (!group.isEmpty()) attributes.put("group-title", group);
        if (!category.isEmpty()) attributes.put("x-highfly-category", category);
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-stable-id", stableId);
        attributes.put("x-resolver-id", slug);
        attributes.put("x-resolver-resource-id", resourceId);
        attributes.put("x-resolver-refresh", "on_play");
        URI reference = DynamicSourceReference.create("highfly", slug);
        if (reference == null) throw new IllegalStateException("Referencia Highfly inválida.");
        URI logo = logoUrl.isEmpty() ? null : URI.create(logoUrl);
        return new Channel(name, reference, logo, group, attributes);
    }

    /** Tokenless M3U reference; the app resolves the HLS source at playback. */
    public String toM3uEntry() {
        StringBuilder header = new StringBuilder("#EXTINF:-1");
        appendAttribute(header, "tvg-id", tvgId());
        if (!logoUrl.isEmpty()) appendAttribute(header, "tvg-logo", logoUrl);
        appendAttribute(header, "x-resolver", "highfly");
        appendAttribute(header, "x-resolver-stable-id", stableId);
        appendAttribute(header, "x-resolver-id", slug);
        appendAttribute(header, "x-resolver-resource-id", resourceId);
        appendAttribute(header, "x-resolver-refresh", "on_play");
        header.append(',').append(cleanLine(name)).append('\n');
        header.append(DynamicSourceReference.create("highfly", slug));
        return header.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("resourceId", resourceId);
        object.put("stableId", stableId);
        object.put("tvgId", tvgId());
        object.put("name", name);
        object.put("group", group);
        object.put("category", category);
        if (!logoUrl.isEmpty()) object.put("logo", logoUrl);
        JSONArray values = new JSONArray();
        for (String genre : genres) values.put(genre);
        object.put("genres", values);
        return object;
    }

    public static HighflyCatalogChannel fromJson(JSONObject object) throws JSONException {
        if (object == null) throw new JSONException("Canal Highfly ausente.");
        List<String> genres = readGenres(object.optJSONArray("genres"));
        String category = object.optString("category", "").trim();
        if (category.isEmpty() && !genres.isEmpty()) category = genres.get(0);
        return new HighflyCatalogChannel(
                object.optString("resourceId", ""),
                object.optString("stableId", object.optString("tvgId", "")),
                object.optString("name", ""),
                object.optString("group", "Highfly"),
                category,
                object.optString("logo", ""),
                genres
        );
    }

    public static HighflyCatalogChannel fromMeta(JSONObject object) throws JSONException {
        if (object == null) throw new JSONException("Meta Highfly ausente.");
        String id = object.optString("id", "").trim();
        String name = object.optString("name", "").trim();
        if (!id.matches("leaf:[A-Za-z0-9_-]{2,128}") || name.isEmpty()) {
            throw new JSONException("Meta Highfly incompleta.");
        }
        List<String> genres = readGenres(object.optJSONArray("genres"));
        String category = genres.isEmpty() ? "Deportes" : genres.get(0);
        return new HighflyCatalogChannel(
                id,
                object.optString("stableId", object.optString("tvgId", "")),
                name,
                "Highfly · Deportes",
                category,
                object.optString(
                        "poster",
                        object.optString("logo", "")
                ),
                genres
        );
    }

    private String tvgId() {
        return stableId;
    }

    /**
     * Returns the stable identity used by the app's local merge and resolver
     * selection. Highfly currently publishes a rotating leaf id but no
     * separate canonical id, so known channels use the identities already
     * used by Lista M3U and unknown names receive a deterministic,
     * slug-independent fallback. The public selection manifest does not
     * export this as a final XMLTV/tvg-id.
     */
    public static String stableIdentity(String requested, String name) {
        String explicit = clean(requested);
        if (explicit.matches("[A-Za-z][A-Za-z0-9._-]{1,127}")) return explicit;

        String normalized = compact(name);
        if (normalized.contains("skysportsf1") || normalized.contains("skyf1")) {
            return "SkySportsF1.uk";
        }
        if (normalized.contains("skysportstennis") || normalized.contains("skytennis")) {
            return "SkySportsTennis.uk";
        }
        if (normalized.contains("skysportspremierleague")
                || normalized.contains("skypremierleague")) {
            return "SkySportsPremierLeague.uk";
        }
        if (normalized.contains("skysportsgolf") || normalized.contains("skygolf")) {
            return "SkySportsGolf.uk";
        }
        if (normalized.equals("espn")) return "ESPN.us";
        if (normalized.equals("marqueesportsnetwork")) {
            return "MarqueeSportsNetwork.us";
        }
        if (normalized.equals("skysport1")) return "SkySport1.nz";
        if (normalized.isEmpty()) return "Highfly.unknown";
        return "Highfly." + normalized.substring(0, Math.min(112, normalized.length()));
    }

    private static String compact(String value) {
        String normalized = Normalizer.normalize(clean(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        return normalized.trim();
    }

    private static List<String> readGenres(JSONArray values) {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            String value = values.optString(index, "").trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static void appendAttribute(StringBuilder output, String key, String value) {
        output.append(' ').append(key).append("=\"")
                .append(cleanLine(value).replace("\"", "&quot;"))
                .append('"');
    }

    private static String cleanLine(String value) {
        return clean(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        return clean(value).isEmpty() ? clean(fallback) : clean(value);
    }

    private static String safeLogoUrl(String value) {
        String candidate = clean(value);
        if (candidate.isEmpty()) return "";
        try {
            URI uri = URI.create(candidate);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "cdn.highfly.to".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    ? uri.toString()
                    : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
