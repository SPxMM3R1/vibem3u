package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable parsed TvVoo catalogue with bounded local search and paging. */
public final class TvVooCatalog {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private final String catalogId;
    private final String countryKey;
    private final List<TvVooCatalogChannel> channels;

    private TvVooCatalog(
            String catalogId,
            String countryKey,
            List<TvVooCatalogChannel> channels
    ) {
        this.catalogId = catalogId == null ? "" : catalogId;
        this.countryKey = TvVooCatalogChannel.countryKey(countryKey);
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
    }

    public String getCatalogId() { return catalogId; }
    public String getCountryKey() { return countryKey; }
    public List<TvVooCatalogChannel> getChannels() { return channels; }
    public int size() { return channels.size(); }

    public List<String> getCategories() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (TvVooCatalogChannel channel : channels) values.addAll(channel.getGenres());
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public List<TvVooCatalogChannel> filter(String search, String category) {
        String query = normalize(search);
        String requestedCategory = normalize(category);
        if ("all".equals(requestedCategory) || "todos".equals(requestedCategory)
                || "tutti".equals(requestedCategory)) requestedCategory = "";
        List<TvVooCatalogChannel> result = new ArrayList<>();
        for (TvVooCatalogChannel channel : channels) {
            if (!query.isEmpty()
                    && !normalize(channel.getName()).contains(query)
                    && !normalize(channel.getAlias()).contains(query)) continue;
            if (!requestedCategory.isEmpty()) {
                boolean categoryMatch = false;
                for (String genre : channel.getGenres()) {
                    if (normalize(genre).equals(requestedCategory)) {
                        categoryMatch = true;
                        break;
                    }
                }
                if (!categoryMatch) continue;
            }
            result.add(channel);
        }
        return Collections.unmodifiableList(result);
    }

    public List<TvVooCatalogChannel> page(String search, String category, int page, int pageSize) {
        List<TvVooCatalogChannel> filtered = filter(search, category);
        int safePageSize = Math.max(1, Math.min(200, pageSize));
        int safePage = Math.max(0, page);
        long startLong = (long) safePage * safePageSize;
        if (startLong >= filtered.size()) return Collections.emptyList();
        int start = (int) startLong;
        int end = Math.min(filtered.size(), start + safePageSize);
        return Collections.unmodifiableList(new ArrayList<>(filtered.subList(start, end)));
    }

    public TvVooCatalogChannel find(String stableId) {
        if (stableId == null) return null;
        for (TvVooCatalogChannel channel : channels) {
            if (stableId.equals(channel.getStableId())) return channel;
        }
        return null;
    }

    public static TvVooCatalog parse(String json, String catalogId, String countryKey)
            throws IOException {
        if (json == null || json.trim().isEmpty()) throw new IOException("Catálogo TvVoo vacío.");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("Catálogo TvVoo demasiado grande.");
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray metas = root.optJSONArray("metas");
            if (metas == null) throw new IOException("Catálogo TvVoo sin canales.");
            Map<String, TvVooCatalogChannel> unique = new LinkedHashMap<>();
            for (int index = 0; index < metas.length(); index++) {
                JSONObject meta = metas.optJSONObject(index);
                if (meta == null || !"tv".equalsIgnoreCase(meta.optString("type", "tv"))) {
                    continue;
                }
                try {
                    TvVooCatalogChannel channel = TvVooCatalogChannel.fromMeta(meta, countryKey);
                    unique.putIfAbsent(channel.getStableId(), channel);
                } catch (JSONException | IllegalArgumentException ignored) {
                    // A malformed row must not hide the rest of a large catalog.
                }
            }
            if (unique.isEmpty()) {
                throw new IOException("Catálogo TvVoo sin canales válidos.");
            }
            return new TvVooCatalog(catalogId, countryKey, new ArrayList<>(unique.values()));
        } catch (JSONException error) {
            throw new IOException("Catálogo TvVoo inválido.", error);
        }
    }

    public static TvVooCatalog parse(String json, String countryKey) throws IOException {
        return parse(json, "vavoo_tv_" + countryKey, countryKey);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
