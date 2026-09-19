package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One stable entry from the official TvVoo/Vavoo IPTV catalogue.
 *
 * <p>The catalogue id/alias is the identity. Display names are deliberately
 * not used to merge entries: HD, FHD, backup and regional feeds can have very
 * similar names while being different resolver targets.</p>
 */
public final class TvVooCatalogChannel {
    private final String stableId;
    private final String alias;
    private final String name;
    private final String country;
    private final String countryKey;
    private final String group;
    private final String category;
    private final String logoUrl;
    private final List<String> genres;
    private final List<String> resolverAliases;

    public TvVooCatalogChannel(
            String stableId,
            String alias,
            String name,
            String country,
            String group,
            String category,
            String logoUrl,
            List<String> resolverAliases
    ) {
        this(
                stableId,
                alias,
                name,
                country,
                group,
                category,
                logoUrl,
                category == null || category.trim().isEmpty()
                        ? Collections.emptyList()
                        : Collections.singletonList(category),
                resolverAliases
        );
    }

    public TvVooCatalogChannel(
            String stableId,
            String alias,
            String name,
            String country,
            String group,
            String category,
            String logoUrl,
            List<String> genres,
            List<String> resolverAliases
    ) {
        this.alias = clean(alias);
        if (this.alias.isEmpty()) throw new IllegalArgumentException("alias");
        this.name = nonBlank(name, this.alias);
        this.country = clean(country);
        this.countryKey = countryKey(this.country);
        this.group = nonBlank(group, this.country);
        this.category = clean(category);
        this.logoUrl = safeHttpUrl(logoUrl);

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

        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(this.alias);
        if (resolverAliases != null) {
            for (String value : resolverAliases) {
                String candidate = clean(value);
                if (!candidate.isEmpty()) aliases.add(candidate);
            }
        }
        this.resolverAliases = Collections.unmodifiableList(new ArrayList<>(aliases));

        String requestedId = clean(stableId);
        this.stableId = requestedId.isEmpty()
                ? buildStableId(this.countryKey, this.alias)
                : requestedId;
    }

    public TvVooCatalogChannel(
            String alias,
            String name,
            String country,
            String group,
            String category,
            List<String> resolverAliases
    ) {
        this(
                buildStableId(countryKey(country), alias),
                alias,
                name,
                country,
                group,
                category,
                "",
                resolverAliases
        );
    }

    public String getStableId() { return stableId; }
    public String getAlias() { return alias; }
    public String getName() { return name; }
    public String getCountry() { return country; }
    public String getCountryKey() { return countryKey; }
    public String getGroup() { return group; }
    public String getCategory() { return category; }
    public String getLogoUrl() { return logoUrl; }
    public List<String> getGenres() { return genres; }
    public List<String> getResolverAliases() { return resolverAliases; }

    /**
     * M3U identity export. It contains no resolved URL, token or password;
     * playback is renewed by the app from x-resolver-id at open time.
     */
    public String toM3uEntry() {
        StringBuilder header = new StringBuilder("#EXTINF:-1");
        appendAttribute(header, "tvg-id", stableId + "@TvVoo");
        if (!logoUrl.isEmpty()) appendAttribute(header, "tvg-logo", logoUrl);
        appendAttribute(header, "x-resolver", "tvvoo");
        appendAttribute(header, "x-resolver-id", alias);
        appendAttribute(header, "x-resolver-ids", alias);
        appendAttribute(header, "x-resolver-country", countryKey);
        appendAttribute(header, "x-resolver-refresh", "on_play");
        header.append(',').append(cleanLine(name)).append('\n');
        header.append("tvvoo://channel/").append(encodePath(stableId));
        return header.toString();
    }

    private static void appendAttribute(StringBuilder output, String key, String value) {
        output.append(' ').append(key).append("=\"")
                .append(cleanLine(value).replace("\"", "&quot;"))
                .append('"');
    }

    private static String cleanLine(String value) {
        return clean(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static String encodePath(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Returns a JSON object containing no resolved or signed playback URL. */
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("stableId", stableId);
        object.put("alias", alias);
        object.put("name", name);
        object.put("country", country);
        object.put("group", group);
        object.put("category", category);
        JSONArray genres = new JSONArray();
        for (String value : this.genres) genres.put(value);
        object.put("genres", genres);
        if (!logoUrl.isEmpty()) object.put("logo", logoUrl);
        JSONArray aliases = new JSONArray();
        for (String value : resolverAliases) aliases.put(value);
        object.put("aliases", aliases);
        return object;
    }

    public static TvVooCatalogChannel fromJson(JSONObject object) throws JSONException {
        if (object == null) throw new JSONException("Canal TvVoo ausente.");
        String alias = object.optString("alias", "").trim();
        String stableId = object.optString("stableId", "").trim();
        String country = object.optString("country", "").trim();
        String group = object.optString("group", "").trim();
        String name = object.optString("name", "").trim();
        String category = object.optString("category", "").trim();
        String logo = object.optString("logo", "").trim();
        List<String> genres = new ArrayList<>();
        JSONArray genresArray = object.optJSONArray("genres");
        if (genresArray != null) {
            for (int index = 0; index < genresArray.length(); index++) {
                String value = genresArray.optString(index, "").trim();
                if (!value.isEmpty()) genres.add(value);
            }
        }
        List<String> aliases = new ArrayList<>();
        JSONArray array = object.optJSONArray("aliases");
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "").trim();
                if (!value.isEmpty()) aliases.add(value);
            }
        }
        return new TvVooCatalogChannel(
                stableId,
                alias,
                name,
                country,
                group,
                category,
                logo,
                genres,
                aliases
        );
    }

    /** Parses one Stremio meta id and returns the resolver's canonical alias. */
    public static TvVooCatalogChannel fromMeta(JSONObject object, String countryKey)
            throws JSONException {
        if (object == null) throw new JSONException("Meta TvVoo ausente.");
        String id = object.optString("id", "").trim();
        String name = object.optString("name", "").trim();
        if (id.isEmpty() || name.isEmpty() || !id.startsWith("vavoo_")) {
            throw new JSONException("Meta TvVoo incompleta.");
        }

        String canonicalAlias = canonicalAlias(id, countryKey);
        String normalizedCountry = countryKey(countryKey);
        String stableId = buildStableId(normalizedCountry, canonicalAlias);
        JSONArray genresArray = object.optJSONArray("genres");
        List<String> genres = new ArrayList<>();
        if (genresArray != null) {
            for (int index = 0; index < genresArray.length(); index++) {
                String genre = genresArray.optString(index, "").trim();
                if (!genre.isEmpty()) genres.add(genre);
            }
        }
        String category = genres.isEmpty() ? "" : genres.get(0);
        String logo = object.optString("logo", "").trim();
        String group = "TvVoo · " + countryDisplayName(normalizedCountry);
        return new TvVooCatalogChannel(
                stableId,
                canonicalAlias,
                name,
                normalizedCountry,
                group,
                category,
                logo,
                genres,
                Collections.singletonList(canonicalAlias)
        );
    }

    /**
     * TvVoo currently publishes ids such as
     * {@code vavoo_SKY%201|group:uk}. The resolver endpoint needs the whole
     * payload percent encoded, including the pipe and colon. Decode once and
     * encode once so raw and already encoded ids have the same stable alias.
     */
    public static String canonicalAlias(String idOrAlias, String countryKey) {
        String value = clean(idOrAlias);
        if (!value.startsWith("vavoo_")) throw new IllegalArgumentException("Alias TvVoo inválido.");
        value = value.substring("vavoo_".length());
        String decoded = decodePercent(value);
        if (!decoded.toLowerCase(Locale.ROOT).contains("|group:")) {
            decoded += "|group:" + groupToken(countryKey);
        }
        return "vavoo_" + encodePart(decoded);
    }

    /**
     * Converts a selected catalogue entry to the app's normal Channel model.
     * The URI is intentionally a non-token placeholder; the TvVoo resolver
     * replaces it before Media3 sees it.
     */
    public Channel toChannel() {
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("tvg-id", stableId + "@TvVoo");
        attributes.put("tvg-name", name);
        if (!country.isEmpty()) attributes.put("tvg-country", country);
        if (!group.isEmpty()) attributes.put("group-title", group);
        if (!category.isEmpty()) attributes.put("x-tvvoo-category", category);
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-id", alias);
        attributes.put("x-resolver-ids", joinAliases(resolverAliases));
        attributes.put("x-resolver-stable-id", stableId);
        URI placeholder = placeholderUri(stableId);
        URI logo = logoUrl.isEmpty() ? null : URI.create(logoUrl);
        return new Channel(name, placeholder, logo, group, attributes);
    }

    public static String buildStableId(String country, String alias) {
        String normalizedCountry = countryKey(country);
        String normalizedAlias = clean(alias);
        if (normalizedCountry.isEmpty()) normalizedCountry = "unknown";
        return normalizedCountry + "|" + normalizedAlias;
    }

    /** Country key used only for exact identity and local filtering. */
    public static String countryKey(String value) {
        String normalized = normalize(value);
        int separator = normalized.indexOf("->");
        if (separator >= 0) normalized = normalized.substring(0, separator);
        separator = normalized.indexOf("➾");
        if (separator >= 0) normalized = normalized.substring(0, separator);
        separator = normalized.indexOf("→");
        if (separator >= 0) normalized = normalized.substring(0, separator);
        return switch (normalized) {
            case "uk", "gb", "greatbritain", "unitedkingdom" -> "unitedkingdom";
            case "us", "usa", "unitedstates" -> "unitedstates";
            case "ar", "argentina" -> "argentina";
            case "cl", "chile" -> "chile";
            case "es", "spain", "espana" -> "spain";
            case "fr", "france" -> "france";
            case "de", "germany", "deutschland" -> "germany";
            case "it", "italy", "italia" -> "italy";
            case "pt", "portugal" -> "portugal";
            case "nl", "netherlands" -> "netherlands";
            case "pl", "poland" -> "poland";
            case "tr", "turkey", "turkiye" -> "turkey";
            case "ie", "ireland" -> "ireland";
            default -> normalized;
        };
    }

    public static String countryDisplayName(String key) {
        return switch (countryKey(key)) {
            case "unitedkingdom" -> "Reino Unido";
            case "unitedstates" -> "Estados Unidos";
            case "argentina" -> "Argentina";
            case "chile" -> "Chile";
            case "spain" -> "España";
            case "france" -> "Francia";
            case "germany" -> "Alemania";
            case "italy" -> "Italia";
            case "portugal" -> "Portugal";
            case "netherlands" -> "Países Bajos";
            case "poland" -> "Polonia";
            case "turkey" -> "Turquía";
            case "ireland" -> "Irlanda";
            case "" -> "Todos los países";
            default -> key == null || key.trim().isEmpty() ? "Todos los países" : key;
        };
    }

    private static String joinAliases(List<String> aliases) {
        StringBuilder result = new StringBuilder();
        for (String alias : aliases) {
            if (alias == null || alias.trim().isEmpty() || alias.indexOf(';') >= 0) continue;
            if (result.length() > 0) result.append(';');
            result.append(alias.trim());
        }
        return result.toString();
    }

    private static String groupToken(String value) {
        String key = countryKey(value);
        switch (key) {
            case "unitedkingdom": return "uk";
            case "unitedstates": return "us";
            case "argentina": return "ar";
            case "chile": return "cl";
            case "spain": return "es";
            case "france": return "fr";
            case "germany": return "de";
            case "italy": return "it";
            case "portugal": return "pt";
            case "netherlands": return "nl";
            case "poland": return "pl";
            case "turkey": return "tr";
            case "ireland": return "ie";
            default: return clean(value).toLowerCase(Locale.ROOT);
        }
    }

    private static String decodePercent(String value) {
        try {
            // URLDecoder treats '+' as a form-space. TvVoo channel names can
            // contain a literal plus, so protect raw plus signs first.
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String encodePart(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static URI placeholderUri(String stableId) {
        String digest = digest(stableId);
        return URI.create("https://example.invalid/tvvoo/" + digest + ".m3u8");
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8 && index < bytes.length; index++) {
                result.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9➾→-]+", "")
                .trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        return clean(value).isEmpty() ? clean(fallback) : clean(value);
    }

    private static String safeHttpUrl(String value) {
        String candidate = clean(value);
        if (candidate.isEmpty()) return "";
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    ? uri.toString()
                    : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
