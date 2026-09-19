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

/** Persists the explicit TvVoo source switch and the user's ordered channels. */
public final class TvVooSelectionStore {
    public static final String PREFS = "tvvoo_selection";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CHANNELS = "channels";
    private static final int MAX_SELECTED_CHANNELS = 2048;

    private final SharedPreferences preferences;

    public TvVooSelectionStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        Context application = context.getApplicationContext();
        preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** TvVoo is opt-in and is disabled on a fresh installation. */
    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * Returns the selected channels in the exact order saved by the user.
     * This method intentionally returns entries even when the source switch is
     * off, so disabling TvVoo never destroys the user's selection.
     */
    public List<Channel> getSelectedChannels() {
        List<Channel> result = new ArrayList<>();
        for (TvVooCatalogChannel channel : getSelectedCatalogChannels()) {
            result.add(channel.toChannel());
        }
        return Collections.unmodifiableList(result);
    }

    public List<TvVooCatalogChannel> getSelectedCatalogChannels() {
        String raw = preferences.getString(KEY_CHANNELS, "");
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(raw);
            Map<String, TvVooCatalogChannel> unique = new LinkedHashMap<>();
            for (int index = 0; index < array.length() && unique.size() < MAX_SELECTED_CHANNELS; index++) {
                try {
                    TvVooCatalogChannel channel = TvVooCatalogChannel.fromJson(array.getJSONObject(index));
                    unique.putIfAbsent(channel.getStableId(), channel);
                } catch (JSONException | IllegalArgumentException ignored) {
                    // Keep valid entries when one old/corrupt row is present.
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(unique.values()));
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
    }

    /** Atomically replaces the selected order; no enabled flag is changed. */
    public void apply(List<TvVooCatalogChannel> channels) {
        JSONArray array = new JSONArray();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        if (channels != null) {
            for (TvVooCatalogChannel channel : channels) {
                if (channel == null || seen.put(channel.getStableId(), Boolean.TRUE) != null) continue;
                if (array.length() >= MAX_SELECTED_CHANNELS) break;
                try {
                    array.put(channel.toJson());
                } catch (JSONException ignored) {
                    // A channel built by the app should always be serializable.
                }
            }
        }
        preferences.edit().putString(KEY_CHANNELS, array.toString()).apply();
    }

    /**
     * Replaces only one country's rows while preserving the other countries
     * and the existing global order. Useful when the catalog screen is scoped
     * to one country.
     */
    public void replaceCountrySelection(
            String countryKey,
            List<TvVooCatalogChannel> countrySelection
    ) {
        String key = TvVooCatalogChannel.countryKey(countryKey);
        List<TvVooCatalogChannel> result = new ArrayList<>();
        for (TvVooCatalogChannel channel : getSelectedCatalogChannels()) {
            if (!channel.getCountryKey().equals(key)) result.add(channel);
        }
        if (countrySelection != null) result.addAll(countrySelection);
        apply(result);
    }

    /** Returns the selected identity entries in persisted order. */
    public String toM3u() {
        StringBuilder result = new StringBuilder("#EXTM3U\n");
        for (TvVooCatalogChannel channel : getSelectedCatalogChannels()) {
            result.append(channel.toM3uEntry()).append('\n');
        }
        return result.toString();
    }

    /** Stable change token for playback/catalogue consumers. */
    public String signature() {
        StringBuilder value = new StringBuilder();
        value.append(isEnabled() ? '1' : '0').append('\n');
        for (TvVooCatalogChannel channel : getSelectedCatalogChannels()) {
            value.append(channel.getStableId()).append('|')
                    .append(channel.getAlias()).append('|')
                    .append(channel.getName()).append('|')
                    .append(channel.getCountry()).append('|')
                    .append(channel.getCountryKey()).append('|')
                    .append(channel.getGroup()).append('|')
                    .append(channel.getCategory()).append('|')
                    .append(channel.getLogoUrl()).append('|')
                    .append(String.join(",", channel.getGenres())).append('|')
                    .append(String.join(";", channel.getResolverAliases())).append('\n');
        }
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
