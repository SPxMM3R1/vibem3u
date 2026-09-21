package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persists the one order used by the playback catalogue.
 *
 * <p>The public M3U remains authoritative for its own membership and
 * presentation data. This store only keeps the user's app order and local
 * removals, so a resolver row can be moved between M3U rows without placing a
 * temporary HLS URL in either repository.</p>
 */
final class ChannelCatalogOrderStore {
    private static final String PREFS = "channel_catalog_order";
    private static final String KEY_ORDER = "order";
    private static final String KEY_REMOVED = "removed";
    private static final String M3U_PREFIX = "m3u:";
    private static final String TVVOO_PREFIX = "tvvoo:";
    private static final String HIGHFLY_PREFIX = "highfly:";

    private final SharedPreferences preferences;

    ChannelCatalogOrderStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Returns the last known global order, excluding no entries. */
    List<String> getOrder() {
        return readArray(KEY_ORDER);
    }

    /** Returns locally removed identities. */
    List<String> getRemoved() {
        return readArray(KEY_REMOVED);
    }

    boolean isRemoved(String key) {
        return !AppStrings.isBlank(key) && new LinkedHashSet<>(getRemoved()).contains(key);
    }

    void applyOrder(List<String> keys) {
        writeArray(KEY_ORDER, keys);
    }

    void markRemoved(String key) {
        if (AppStrings.isBlank(key)) return;
        LinkedHashSet<String> removed = new LinkedHashSet<>(getRemoved());
        if (!removed.add(key)) return;
        writeArray(KEY_REMOVED, new ArrayList<>(removed));
    }

    /**
     * Applies the saved order to the merged playback list. New channels keep
     * their source order and are appended after known identities. Removed
     * identities stay out of playback until the user adds them again through a
     * provider catalogue or clears the local removal.
     */
    List<Channel> applyToPlayback(List<Channel> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        Set<String> removed = new LinkedHashSet<>(getRemoved());
        List<Channel> visible = new ArrayList<>();
        for (Channel channel : input) {
            if (channel == null || removed.contains(keyFor(channel))) continue;
            visible.add(channel);
        }
        if (visible.size() < 2) return Collections.unmodifiableList(visible);

        List<String> savedOrder = getOrder();
        if (savedOrder.isEmpty()) return Collections.unmodifiableList(visible);
        java.util.Map<String, List<Channel>> byKey = new java.util.LinkedHashMap<>();
        for (Channel channel : visible) {
            byKey.computeIfAbsent(keyFor(channel), ignored -> new ArrayList<>()).add(channel);
        }
        List<Channel> result = new ArrayList<>(visible.size());
        Set<String> emitted = new LinkedHashSet<>();
        for (String key : savedOrder) {
            List<Channel> matches = byKey.get(key);
            if (matches == null || !emitted.add(key)) continue;
            result.addAll(matches);
        }
        for (Channel channel : visible) {
            String key = keyFor(channel);
            if (emitted.add(key)) result.addAll(byKey.get(key));
        }
        return Collections.unmodifiableList(result);
    }

    static String keyFor(TvVooCatalogChannel channel) {
        return channel == null ? "" : TVVOO_PREFIX + channel.getStableId();
    }

    static String keyFor(HighflyCatalogChannel channel) {
        // catalogKey/stableId is the durable identity. The leaf is deliberately
        // excluded because Highfly may rotate it between catalog refreshes.
        return channel == null ? "" : HIGHFLY_PREFIX + channel.getStableId();
    }

    static String keyFor(Channel channel) {
        if (channel == null) return "";
        if (TvVooChannelMerge.isTvVoo(channel)) {
            String stable = channel.getAttributes().get("x-resolver-stable-id");
            if (AppStrings.isBlank(stable)) stable = stripTvVooSuffix(channel.getTvgId());
            if (!AppStrings.isBlank(stable)) return TVVOO_PREFIX + stable.trim();
        }
        if (HighflyChannelMerge.isHighfly(channel)) {
            String stable = channel.getAttributes().get("x-resolver-stable-id");
            if (AppStrings.isBlank(stable)) stable = channel.getTvgId();
            if (!AppStrings.isBlank(stable)) return HIGHFLY_PREFIX + stable.trim();
        }
        String tvgId = channel.getTvgId();
        if (!AppStrings.isBlank(tvgId)) return M3U_PREFIX + "tvg:" + tvgId.trim();
        return M3U_PREFIX + "uri:" + sha256(String.valueOf(channel.getStreamUri()));
    }

    private List<String> readArray(String key) {
        String raw = preferences.getString(key, "");
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(raw);
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "").trim();
                if (!value.isEmpty() && value.length() <= 512) values.add(value);
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void writeArray(String key, List<String> values) {
        JSONArray array = new JSONArray();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) continue;
                if (unique.add(value.trim())) array.put(value.trim());
            }
        }
        preferences.edit().putString(key, array.toString()).apply();
    }

    private static String stripTvVooSuffix(String value) {
        String clean = value == null ? "" : value.trim();
        String suffix = "@TvVoo";
        return clean.endsWith(suffix)
                ? clean.substring(0, clean.length() - suffix.length())
                : clean;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
