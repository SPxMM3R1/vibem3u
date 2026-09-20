package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cross-references app catalogue identities with the tvg-id published by the
 * configured M3U sources.
 *
 * <p>The app never invents a public XMLTV id here. A value is considered
 * confirmed only when it was read from a current or cached M3U entry. This
 * keeps Lista M3U as the authority for EPG ids while still making the pending
 * state visible in the catalogue selectors.</p>
 */
public final class ChannelIdentityIndex {
    private final Map<String, String> values;
    private final int entriesRead;
    private final int sourcesRead;

    private ChannelIdentityIndex(Map<String, String> values, int entriesRead, int sourcesRead) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.entriesRead = Math.max(0, entriesRead);
        this.sourcesRead = Math.max(0, sourcesRead);
    }

    public static ChannelIdentityIndex empty() {
        return new ChannelIdentityIndex(Collections.emptyMap(), 0, 0);
    }

    /** Builds an index from already parsed playlists; kept package-visible for tests. */
    static ChannelIdentityIndex fromPlaylists(List<Playlist> playlists) {
        Map<String, String> values = new LinkedHashMap<>();
        int entries = 0;
        int sources = 0;
        if (playlists != null) {
            for (Playlist playlist : playlists) {
                if (playlist == null) continue;
                sources++;
                for (Channel channel : playlist.getChannels()) {
                    if (channel == null || AppStrings.isBlank(channel.getTvgId())) continue;
                    entries++;
                    index(values, channel);
                }
            }
        }
        return new ChannelIdentityIndex(values, entries, sources);
    }

    /** Reads only the local M3U cache and never performs network I/O. */
    public static ChannelIdentityIndex loadCached(Context context) {
        return load(context);
    }

    /**
     * Refreshes the enabled M3U sources and then reads their sanitized cache.
     * A failed source does not discard identities obtained from another source.
     */
    public static RefreshResult refresh(Context context) {
        if (context == null) return new RefreshResult(empty(), 0, 0);
        Context application = context.getApplicationContext();
        SharedPreferences preferences = application.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        PlaylistRepository repository = new PlaylistRepository(application);
        List<String> urls = enabledUrls(preferences);
        int attempted = 0;
        int failures = 0;
        for (String url : urls) {
            attempted++;
            try {
                repository.downloadIfChanged(url);
            } catch (IOException | RuntimeException ignored) {
                failures++;
            }
        }
        return new RefreshResult(load(application), attempted, failures);
    }

    public String tvgIdFor(TvVooCatalogChannel channel) {
        if (channel == null) return "";
        String result = first(
                "stable:" + channel.getStableId(),
                "resolver:" + channel.getAlias(),
                "tvg:" + channel.getStableId() + "@TvVoo"
        );
        return result;
    }

    public String tvgIdFor(HighflyCatalogChannel channel) {
        if (channel == null) return "";
        return first(
                "stable:" + channel.getStableId(),
                "resource:" + channel.getResourceId(),
                "resolver:" + channel.getSlug(),
                "tvg:" + channel.getStableId()
        );
    }

    public int getEntriesRead() { return entriesRead; }
    public int getSourcesRead() { return sourcesRead; }

    private String first(String... keys) {
        if (keys == null) return "";
        for (String key : keys) {
            String value = values.get(normalizedKey(key));
            if (!AppStrings.isBlank(value)) return value;
        }
        return "";
    }

    private static ChannelIdentityIndex load(Context context) {
        if (context == null) return empty();
        SharedPreferences preferences = context.getApplicationContext().getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        List<String> urls = enabledUrls(preferences);
        List<Playlist> playlists = new ArrayList<>();
        for (String url : urls) {
            try {
                Playlist playlist = new PlaylistRepository(context).loadCached(url);
                if (playlist == null) continue;
                playlists.add(playlist);
            } catch (IOException | RuntimeException ignored) {
                // One stale cache must not hide identities from another source.
            }
        }
        return fromPlaylists(playlists);
    }

    private static void index(Map<String, String> values, Channel channel) {
        String tvgId = channel.getTvgId() == null ? "" : channel.getTvgId().trim();
        if (AppStrings.isBlank(tvgId)) return;
        Map<String, String> attributes = channel.getAttributes();
        putIfAbsent(values, "tvg:" + tvgId, tvgId);
        putIfAbsent(values, "stable:" + value(attributes, "x-resolver-stable-id"), tvgId);
        putIfAbsent(values, "resource:" + value(attributes, "x-resolver-resource-id"), tvgId);
        putIfAbsent(values, "resolver:" + value(attributes, "x-resolver-id"), tvgId);
        putIfAbsent(values, "resolver:" + value(attributes, "x-resolver-slug"), tvgId);
    }

    private static String value(Map<String, String> attributes, String key) {
        if (attributes == null || key == null) return "";
        String value = attributes.get(key);
        return value == null ? "" : value.trim();
    }

    private static void putIfAbsent(Map<String, String> values, String key, String tvgId) {
        String normalized = normalizedKey(key);
        if (normalized.endsWith(":")) return;
        values.putIfAbsent(normalized, tvgId);
    }

    private static String normalizedKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> enabledUrls(SharedPreferences preferences) {
        if (preferences == null) return Collections.emptyList();
        List<String> result = new ArrayList<>(2);
        if (preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, true)) {
            addUrl(result, preferences.getString(SettingsActivity.KEY_PLAYLIST_URL, ""));
        }
        if (preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false)) {
            addUrl(result, preferences.getString(SettingsActivity.KEY_PLAYLIST_URL_2, ""));
        }
        return result;
    }

    private static void addUrl(List<String> urls, String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || urls.contains(candidate)) return;
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return;
        urls.add(candidate);
    }

    public static final class RefreshResult {
        private final ChannelIdentityIndex index;
        private final int attemptedSources;
        private final int failedSources;

        RefreshResult(ChannelIdentityIndex index, int attemptedSources, int failedSources) {
            this.index = index == null ? empty() : index;
            this.attemptedSources = Math.max(0, attemptedSources);
            this.failedSources = Math.max(0, failedSources);
        }

        public ChannelIdentityIndex getIndex() { return index; }
        public int getAttemptedSources() { return attemptedSources; }
        public int getFailedSources() { return failedSources; }
    }
}
