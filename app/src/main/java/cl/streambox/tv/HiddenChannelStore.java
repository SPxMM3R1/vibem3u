package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persistent, token-free list of channels hidden by the user.
 *
 * <p>The channel identity prefers {@code tvg-id}. If a playlist does not
 * provide one, only the public URI shape (scheme/host/path), name and group
 * are hashed; query strings and fragments are deliberately excluded so a
 * signed playback URL cannot be persisted as a hidden-channel key.</p>
 */
final class HiddenChannelStore {
    private static final String PREFS = "hidden_channels";
    private static final String KEY_IDENTITIES = "identities";
    private static final String KEY_NAME_PREFIX = "name_";
    private static final String KEY_TVG_ID_PREFIX = "tvg_";

    private final SharedPreferences preferences;

    HiddenChannelStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isHidden(Channel channel) {
        return channel != null && isHidden(identity(channel));
    }

    boolean isHidden(String channelIdentity) {
        if (AppStrings.isBlank(channelIdentity)) return false;
        return identities().contains(channelIdentity);
    }

    void setHidden(Channel channel, boolean hidden) {
        if (channel == null) return;
        String identity = identity(channel);
        SharedPreferences.Editor editor = preferences.edit();
        LinkedHashSet<String> identities = identities();
        String key = metadataKey(identity);
        if (hidden) {
            identities.add(identity);
            editor.putString(KEY_NAME_PREFIX + key, safeName(channel.getName()))
                    .putString(KEY_TVG_ID_PREFIX + key, safeName(channel.getTvgId()));
        } else {
            identities.remove(identity);
            editor.remove(KEY_NAME_PREFIX + key)
                    .remove(KEY_TVG_ID_PREFIX + key);
        }
        editor.putStringSet(KEY_IDENTITIES, identities).apply();
    }

    void setHidden(String channelIdentity, boolean hidden) {
        if (AppStrings.isBlank(channelIdentity)) return;
        LinkedHashSet<String> identities = identities();
        SharedPreferences.Editor editor = preferences.edit();
        String key = metadataKey(channelIdentity);
        if (hidden) {
            identities.add(channelIdentity);
        } else {
            identities.remove(channelIdentity);
            editor.remove(KEY_NAME_PREFIX + key)
                    .remove(KEY_TVG_ID_PREFIX + key);
        }
        editor.putStringSet(KEY_IDENTITIES, identities).apply();
    }

    List<Entry> getEntries() {
        List<Entry> result = new ArrayList<>();
        for (String identity : identities()) {
            String key = metadataKey(identity);
            String name = preferences.getString(KEY_NAME_PREFIX + key, "Canal oculto");
            String tvgId = preferences.getString(KEY_TVG_ID_PREFIX + key, "");
            result.add(new Entry(identity, name, tvgId));
        }
        Collections.sort(result, (left, right) -> {
            int byName = left.name.compareToIgnoreCase(right.name);
            return byName != 0 ? byName : left.identity.compareTo(right.identity);
        });
        return Collections.unmodifiableList(result);
    }

    static String identity(Channel channel) {
        String tvgId = channel == null ? "" : safeName(channel.getTvgId());
        if (!AppStrings.isBlank(tvgId)) return "tvg:" + tvgId;
        if (channel == null) return "";
        URI uri = channel.getStreamUri();
        StringBuilder stable = new StringBuilder();
        stable.append(safeName(channel.getName())).append('|')
                .append(safeName(channel.getGroup())).append('|');
        if (uri != null) {
            stable.append(safeName(uri.getScheme())).append("://")
                    .append(safeName(uri.getHost())).append(safeName(uri.getPath()));
        }
        return "channel:" + sha256(stable.toString());
    }

    private LinkedHashSet<String> identities() {
        Set<String> stored = preferences.getStringSet(KEY_IDENTITIES, Collections.emptySet());
        return new LinkedHashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    private static String metadataKey(String identity) {
        return sha256(identity);
    }

    private static String safeName(String value) {
        return value == null ? "" : value.trim();
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

    static final class Entry {
        private final String identity;
        private final String name;
        private final String tvgId;

        Entry(String identity, String name, String tvgId) {
            this.identity = identity == null ? "" : identity;
            this.name = AppStrings.isBlank(name) ? "Canal oculto" : name;
            this.tvgId = tvgId == null ? "" : tvgId;
        }

        String getIdentity() { return identity; }
        String getName() { return name; }
        String getTvgId() { return tvgId; }
    }
}
