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

/** Immutable Highfly live catalogue with bounded local filtering. */
public final class HighflyCatalog {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private final List<HighflyCatalogChannel> channels;

    private HighflyCatalog(List<HighflyCatalogChannel> channels) {
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
    }

    public List<HighflyCatalogChannel> getChannels() { return channels; }
    public int size() { return channels.size(); }

    public List<String> getCategories() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (HighflyCatalogChannel channel : channels) result.addAll(channel.getGenres());
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    public List<HighflyCatalogChannel> filter(String search, String category) {
        String query = normalize(search);
        String requestedCategory = normalize(category);
        if ("todos".equals(requestedCategory) || "all".equals(requestedCategory)) {
            requestedCategory = "";
        }
        List<HighflyCatalogChannel> result = new ArrayList<>();
        for (HighflyCatalogChannel channel : channels) {
            if (!query.isEmpty()
                    && !normalize(channel.getName()).contains(query)
                    && !normalize(channel.getSlug()).contains(query)) continue;
            if (!requestedCategory.isEmpty()) {
                boolean match = false;
                for (String genre : channel.getGenres()) {
                    if (normalize(genre).equals(requestedCategory)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
            }
            result.add(channel);
        }
        return Collections.unmodifiableList(result);
    }

    public static HighflyCatalog parse(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("Catálogo Highfly vacío.");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("Catálogo Highfly demasiado grande.");
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray metas = root.optJSONArray("metas");
            if (metas == null) throw new IOException("Catálogo Highfly sin canales.");
            Map<String, HighflyCatalogChannel> unique = new LinkedHashMap<>();
            for (int index = 0; index < metas.length(); index++) {
                JSONObject meta = metas.optJSONObject(index);
                if (meta == null) continue;
                try {
                    HighflyCatalogChannel channel = HighflyCatalogChannel.fromMeta(meta);
                    unique.putIfAbsent(channel.getResourceId(), channel);
                } catch (JSONException | IllegalArgumentException ignored) {
                    // One stale event must not hide the rest of the catalogue.
                }
            }
            if (unique.isEmpty()) throw new IOException("Catálogo Highfly sin canales válidos.");
            return new HighflyCatalog(new ArrayList<>(unique.values()));
        } catch (JSONException error) {
            throw new IOException("Catálogo Highfly inválido.", error);
        }
    }

    /** Serializes only stable metadata for the disk cache. */
    public String toJson() {
        JSONObject root = new JSONObject();
        JSONArray metas = new JSONArray();
        for (HighflyCatalogChannel channel : channels) {
            try {
                JSONObject meta = channel.toJson();
                meta.put("id", channel.getResourceId());
                meta.put("type", "sport");
                meta.put("name", channel.getName());
                if (!channel.getLogoUrl().isEmpty()) meta.put("poster", channel.getLogoUrl());
                metas.put(meta);
            } catch (JSONException ignored) {
                // Locally created metadata is serializable; skip only a corrupt row.
            }
        }
        try {
            root.put("metas", metas);
        } catch (JSONException impossible) {
            return "{\"metas\":[]}";
        }
        return root.toString();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "")
                .trim();
    }
}
