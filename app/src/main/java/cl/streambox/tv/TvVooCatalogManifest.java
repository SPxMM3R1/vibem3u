package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** The small, trusted subset of the TvVoo Stremio manifest used by the app. */
public final class TvVooCatalogManifest {
    public static final int MAX_BYTES = 512 * 1024;
    private static final String CATALOG_PREFIX = "vavoo_tv_";

    private final String version;
    private final List<Catalog> catalogs;

    private TvVooCatalogManifest(String version, List<Catalog> catalogs) {
        this.version = version;
        this.catalogs = Collections.unmodifiableList(new ArrayList<>(catalogs));
    }

    public String getVersion() { return version; }
    public List<Catalog> getCatalogs() { return catalogs; }

    public Catalog findByCountry(String countryKey) {
        String key = TvVooCatalogChannel.countryKey(countryKey);
        for (Catalog catalog : catalogs) {
            if (catalog.getCountryKey().equals(key)) return catalog;
        }
        return null;
    }

    public static TvVooCatalogManifest parse(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) throw new IOException("Manifest TvVoo vacío.");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("Manifest TvVoo demasiado grande.");
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("catalogs");
            if (array == null || array.length() == 0 || array.length() > 128) {
                throw new IOException("Manifest TvVoo sin catálogos.");
            }
            String version = root.optString("version", "").trim();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            List<Catalog> result = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null || !"tv".equalsIgnoreCase(item.optString("type", ""))) {
                    continue;
                }
                String id = item.optString("id", "").trim().toLowerCase(Locale.ROOT);
                if (!id.startsWith(CATALOG_PREFIX)
                        || id.length() <= CATALOG_PREFIX.length()
                        || !id.matches("vavoo_tv_[a-z0-9_-]{1,32}")) {
                    continue;
                }
                if (!ids.add(id)) continue;
                String country = id.substring(CATALOG_PREFIX.length());
                String name = item.optString("name", "").trim();
                if (name.isEmpty()) name = TvVooCatalogChannel.countryDisplayName(country);
                List<String> genres = parseGenres(item.optJSONArray("extra"));
                result.add(new Catalog(id, country, displayName(name), genres));
            }
            if (result.isEmpty()) throw new IOException("Manifest TvVoo sin catálogos de TV.");
            return new TvVooCatalogManifest(version, result);
        } catch (JSONException error) {
            throw new IOException("Manifest TvVoo inválido.", error);
        }
    }

    private static List<String> parseGenres(JSONArray extras) throws JSONException {
        if (extras == null) return Collections.emptyList();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int index = 0; index < extras.length(); index++) {
            JSONObject extra = extras.optJSONObject(index);
            if (extra == null || !"genre".equalsIgnoreCase(extra.optString("name", ""))) {
                continue;
            }
            JSONArray options = extra.optJSONArray("options");
            if (options == null) continue;
            for (int option = 0; option < options.length(); option++) {
                String value = options.optString(option, "").trim();
                if (!value.isEmpty() && !"tutti".equalsIgnoreCase(value)
                        && !"todos".equalsIgnoreCase(value)
                        && !"all".equalsIgnoreCase(value)) result.add(value);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    private static String displayName(String value) {
        String result = value.trim();
        String prefix = "Vavoo TV •";
        if (result.regionMatches(true, 0, prefix, 0, prefix.length())) {
            result = result.substring(prefix.length()).trim();
        }
        return result.isEmpty() ? "TvVoo" : result;
    }

    public static final class Catalog {
        private final String id;
        private final String countryKey;
        private final String displayName;
        private final List<String> genres;

        Catalog(String id, String country, String displayName, List<String> genres) {
            this.id = id;
            this.countryKey = TvVooCatalogChannel.countryKey(country);
            this.displayName = displayName;
            this.genres = Collections.unmodifiableList(new ArrayList<>(genres));
        }

        public String getId() { return id; }
        public String getCountryKey() { return countryKey; }
        public String getDisplayName() { return displayName; }
        public List<String> getGenres() { return genres; }
    }
}
