package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Persists the opt-in Highfly catalogue selection without playback URLs. */
public final class HighflySelectionStore {
    public static final String PREFS = "highfly_selection";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CHANNELS = "channels";
    private static final int MAX_SELECTED_CHANNELS = 2048;

    private final SharedPreferences preferences;

    public HighflySelectionStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return preferences.getBoolean(KEY_ENABLED, false); }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public List<Channel> getSelectedChannels() {
        List<Channel> result = new ArrayList<>();
        for (HighflyCatalogChannel channel : getSelectedCatalogChannels()) {
            result.add(channel.toChannel());
        }
        return Collections.unmodifiableList(result);
    }

    public List<HighflyCatalogChannel> getSelectedCatalogChannels() {
        String raw = preferences.getString(KEY_CHANNELS, "");
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(raw);
            Map<String, HighflyCatalogChannel> unique = new LinkedHashMap<>();
            for (int index = 0; index < array.length()
                    && unique.size() < MAX_SELECTED_CHANNELS; index++) {
                try {
                    HighflyCatalogChannel channel = HighflyCatalogChannel.fromJson(
                            array.getJSONObject(index)
                    );
                    unique.putIfAbsent(channel.getResourceId(), channel);
                } catch (JSONException | IllegalArgumentException ignored) {
                    // Preserve valid selections if one old row is malformed.
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(unique.values()));
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
    }

    public void apply(List<HighflyCatalogChannel> channels) {
        JSONArray array = new JSONArray();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        if (channels != null) {
            for (HighflyCatalogChannel channel : channels) {
                if (channel == null || seen.put(channel.getResourceId(), Boolean.TRUE) != null) {
                    continue;
                }
                if (array.length() >= MAX_SELECTED_CHANNELS) break;
                try {
                    array.put(channel.toJson());
                } catch (JSONException ignored) {
                    // App-created metadata is serializable.
                }
            }
        }
        preferences.edit().putString(KEY_CHANNELS, array.toString()).apply();
    }

    public String toM3u() {
        StringBuilder result = new StringBuilder("#EXTM3U\n");
        for (HighflyCatalogChannel channel : getSelectedCatalogChannels()) {
            result.append(channel.toM3uEntry()).append('\n');
        }
        return result.toString();
    }

    public String signature() {
        StringBuilder value = new StringBuilder();
        value.append(isEnabled() ? '1' : '0').append('\n');
        for (HighflyCatalogChannel channel : getSelectedCatalogChannels()) {
            value.append(channel.getStableId()).append('|')
                    .append(channel.getResourceId()).append('|')
                    .append(channel.getSlug()).append('|')
                    .append(channel.getName()).append('|')
                    .append(channel.getGroup()).append('|')
                    .append(channel.getCategory()).append('|')
                    .append(channel.getLogoUrl()).append('|')
                    .append(String.join(",", channel.getGenres())).append('\n');
        }
        return sha256(value.toString());
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
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
