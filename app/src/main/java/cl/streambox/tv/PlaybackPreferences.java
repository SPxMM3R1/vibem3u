package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

final class PlaybackPreferences {
    private static final String PREFS = "playback_state";
    private static final String KEY_LAST_CHANNEL = "last_channel";
    private static final String KEY_LAST_INDEX = "last_index";
    /**
     * Copia del último canal (0.5.87): al encender, la app empieza a resolverlo antes de tener
     * la lista armada. Sin respaldos ni tokens: los mismos datos que publica la lista.
     */
    private static final String KEY_LAST_SNAPSHOT = "last_channel_snapshot";
    /**
     * 0.6.23: lista del último canal guardado. Solo se guarda Lista 1 (1); un valor viejo
     * sin esta marca puede ser de Lista 2 y su copia no se usa para precalentar.
     */
    private static final String KEY_LAST_LIST = "last_channel_list";
    static final int REMEMBERED_LIST = 1;
    private static final String QUALITY_PREFIX = "quality_";
    private static final String AUTOMATIC_QUALITY_VALUE = "auto";
    private static final String SUBTITLES_PREFIX = "subtitles_";
    /** Pista de audio elegida en Opciones (0.6.11), por canal: clave de AudioTrackOptions. */
    private static final String AUDIO_PREFIX = "audio_";
    /** Sincronía del audio por canal en ms (0.6.29); sin clave = 0. */
    private static final String AUDIO_SYNC_PREFIX = "avsync_";
    /** Señal elegida a mano en «Fuentes y calidades» (0.5.75). */
    private static final String SOURCE_PREFIX = "source_";
    static final String SOURCE_PRINCIPAL = "principal";
    static final String SOURCE_TVVOO_BACKUP = "tvvoo";
    static final String SOURCE_DIRECT_BACKUP_PREFIX = "backup:";
    /** Fuente elegida de un resolutor sin versiones propias (Highfly…), por nombre. */
    private static final String RESOLVER_SOURCE_PREFIX = "resolver_source_";

    static final class QualityPreference {
        final int bitrate;
        final int width;
        final int height;

        QualityPreference(int bitrate, int width, int height) {
            this.bitrate = bitrate;
            this.width = width;
            this.height = height;
        }
    }

    private final SharedPreferences preferences;

    private static final String KEY_AUTO_BACKUP = "auto_backup_enabled";

    /** «Cambio automático» del selector (0.5.95): pasar sola a un respaldo si la señal cae. */
    boolean isAutoBackupEnabled() {
        return preferences.getBoolean(KEY_AUTO_BACKUP, true);
    }

    void setAutoBackupEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply();
    }

    PlaybackPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    int findInitialChannelIndex(List<Channel> channels) {
        return findChannelIndex(
                channels,
                preferences.getString(KEY_LAST_CHANNEL, ""),
                preferences.getInt(KEY_LAST_INDEX, 0)
        );
    }

    void rememberChannel(Channel channel, int index) {
        preferences.edit()
                .putString(KEY_LAST_CHANNEL, channelIdentity(channel))
                .putInt(KEY_LAST_INDEX, index)
                .putString(KEY_LAST_SNAPSHOT, snapshotJson(channel))
                .putInt(KEY_LAST_LIST, REMEMBERED_LIST)
                .apply();
    }

    /** El último canal visto, reconstruido de su copia; null si no hay o está dañada. */
    Channel lastChannelSnapshot() {
        if (preferences.getInt(KEY_LAST_LIST, 0) != REMEMBERED_LIST) return null;
        return parseSnapshot(preferences.getString(KEY_LAST_SNAPSHOT, ""));
    }

    static String snapshotJson(Channel channel) {
        if (channel == null || channel.getStreamUri() == null) return "";
        try {
            org.json.JSONObject attributes = new org.json.JSONObject();
            for (java.util.Map.Entry<String, String> entry : channel.getAttributes().entrySet()) {
                String key = entry.getKey();
                if (key == null || entry.getValue() == null || key.startsWith("x-backup")) continue;
                attributes.put(key, entry.getValue());
            }
            return new org.json.JSONObject()
                    .put("name", channel.getName())
                    .put("uri", channel.getStreamUri().toString())
                    .put("logo", channel.getLogoUri() == null ? "" : channel.getLogoUri().toString())
                    .put("group", channel.getGroup() == null ? "" : channel.getGroup())
                    .put("attributes", attributes)
                    .toString();
        } catch (org.json.JSONException | RuntimeException error) {
            return "";
        }
    }

    static Channel parseSnapshot(String json) {
        if (json == null || json.isEmpty() || json.length() > 16 * 1024) return null;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
            org.json.JSONObject values = root.optJSONObject("attributes");
            if (values != null) {
                java.util.Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    attributes.put(key, values.optString(key, ""));
                }
            }
            String logo = root.optString("logo", "");
            return new Channel(
                    root.getString("name"),
                    java.net.URI.create(root.getString("uri")),
                    logo.isEmpty() ? null : java.net.URI.create(logo),
                    root.optString("group", ""),
                    attributes);
        } catch (org.json.JSONException | RuntimeException error) {
            return null;
        }
    }

    QualityPreference getQuality(Channel channel) {
        String value = preferences.getString(qualityKey(channel), "");
        if (value == null || AppStrings.isBlank(value) || AUTOMATIC_QUALITY_VALUE.equals(value)) {
            return null;
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 3) return null;
        try {
            return new QualityPreference(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    void rememberQuality(Channel channel, int bitrate, int width, int height) {
        preferences.edit()
                .putString(qualityKey(channel), bitrate + "," + width + "," + height)
                .apply();
    }

    boolean isAutomaticQuality(Channel channel) {
        return AUTOMATIC_QUALITY_VALUE.equals(
                preferences.getString(qualityKey(channel), "")
        );
    }

    void useAutomaticQuality(Channel channel) {
        preferences.edit()
                .putString(qualityKey(channel), AUTOMATIC_QUALITY_VALUE)
                .apply();
    }

    /** Última señal elegida para el canal, o "" si nunca se eligió una. */
    String getSourceChoice(Channel channel) {
        String value = preferences.getString(sourceKey(channel), "");
        return value == null ? "" : value;
    }

    void rememberSourceChoice(Channel channel, String value) {
        if (value == null || AppStrings.isBlank(value)) {
            preferences.edit().remove(sourceKey(channel)).apply();
        } else {
            preferences.edit().putString(sourceKey(channel), value).apply();
        }
    }

    /** Nombre de la fuente elegida en el selector para ese resolutor, o "". */
    String getResolverSourceChoice(Channel channel, String resolverId) {
        String value = preferences.getString(resolverSourceKey(channel, resolverId), "");
        return value == null ? "" : value;
    }

    void rememberResolverSourceChoice(Channel channel, String resolverId, String label) {
        String key = resolverSourceKey(channel, resolverId);
        if (label == null || AppStrings.isBlank(label) || label.length() > 200) {
            preferences.edit().remove(key).apply();
        } else {
            preferences.edit().putString(key, label).apply();
        }
    }

    private static String resolverSourceKey(Channel channel, String resolverId) {
        return RESOLVER_SOURCE_PREFIX + sha256(channelIdentity(channel) + "|" + resolverId);
    }

    /** Respaldo directo identificado por la huella de su dirección (no se guarda la URL). */
    static String directBackupChoice(java.net.URI uri) {
        return SOURCE_DIRECT_BACKUP_PREFIX + sha256(String.valueOf(uri));
    }

    boolean getSubtitles(Channel channel) {
        return preferences.getBoolean(subtitlesKey(channel), true);
    }

    void rememberSubtitles(Channel channel, boolean enabled) {
        preferences.edit().putBoolean(subtitlesKey(channel), enabled).apply();
    }

    String getAudio(Channel channel) {
        return preferences.getString(AUDIO_PREFIX + sha256(channelIdentity(channel)), "");
    }

    void rememberAudio(Channel channel, String key) {
        String preferenceKey = AUDIO_PREFIX + sha256(channelIdentity(channel));
        if (key == null || key.isEmpty()) {
            preferences.edit().remove(preferenceKey).apply();
        } else {
            preferences.edit().putString(preferenceKey, key).apply();
        }
    }

    int getAudioSyncMs(Channel channel) {
        return AudioSyncOffset.clampMs(preferences.getInt(
                AUDIO_SYNC_PREFIX + sha256(channelIdentity(channel)), 0));
    }

    void rememberAudioSyncMs(Channel channel, int valueMs) {
        String preferenceKey = AUDIO_SYNC_PREFIX + sha256(channelIdentity(channel));
        int value = AudioSyncOffset.clampMs(valueMs);
        if (value == 0) {
            preferences.edit().remove(preferenceKey).apply();
        } else {
            preferences.edit().putInt(preferenceKey, value).apply();
        }
    }

    static int findChannelIndex(List<Channel> channels, String savedIdentity, int fallbackIndex) {
        if (channels.isEmpty()) return 0;
        if (savedIdentity != null && !AppStrings.isBlank(savedIdentity)) {
            for (int index = 0; index < channels.size(); index++) {
                if (savedIdentity.equals(channelIdentity(channels.get(index)))) return index;
            }
        }
        return Math.max(0, Math.min(fallbackIndex, channels.size() - 1));
    }

    static String channelIdentity(Channel channel) {
        String tvgId = channel.getTvgId();
        return !AppStrings.isBlank(tvgId)
                ? "tvg:" + tvgId
                : "uri:" + channel.getStreamUri();
    }

    private static String qualityKey(Channel channel) {
        return QUALITY_PREFIX + sha256(channelIdentity(channel));
    }

    private static String sourceKey(Channel channel) {
        return SOURCE_PREFIX + sha256(channelIdentity(channel));
    }

    private static String subtitlesKey(Channel channel) {
        return SUBTITLES_PREFIX + sha256(channelIdentity(channel));
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
