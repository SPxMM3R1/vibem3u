package cl.streambox.tv;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Sanitized in-memory representation of the protected Highfly catalog. */
public final class HighflyPremiumCatalog {
    private static final URI EVENT_PLACEHOLDER_BASE = URI.create(
            "https://premium.highfly.dev/premium-event/"
    );

    private final List<Entry> entries;
    private final HighflyPremiumPreferences.Region region;
    private final long loadedAtMillis;

    HighflyPremiumCatalog(
            List<Entry> entries,
            HighflyPremiumPreferences.Region region,
            long loadedAtMillis
    ) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.region = region == null ? HighflyPremiumPreferences.Region.MAIN : region;
        this.loadedAtMillis = Math.max(0L, loadedAtMillis);
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public HighflyPremiumPreferences.Region getRegion() {
        return region;
    }

    public long getLoadedAtMillis() {
        return loadedAtMillis;
    }

    public int count(EntryType type) {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.getType() == type) count++;
        }
        return count;
    }

    public List<Entry> getVisibleEntries(boolean includeEvents) {
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.getType() == EntryType.STABLE_CHANNEL
                    || includeEvents && entry.getType() == EntryType.TEMPORARY_EVENT) {
                visible.add(entry);
            }
        }
        return Collections.unmodifiableList(visible);
    }

    /**
     * Compatibility view containing stable entries and, when requested, every
     * event. The application uses {@link #toStablePlaylist()} and
     * {@link #toEventsPlaylist(Set)} to keep Lista 3 and Lista 4 separate.
     */
    public Playlist toPlaylist(boolean includeEvents) {
        LinkedHashMap<String, Boolean> selectedEvents = new LinkedHashMap<>();
        if (includeEvents) {
            for (Entry entry : entries) {
                if (entry.getType() == EntryType.TEMPORARY_EVENT) {
                    selectedEvents.put(entry.getId(), Boolean.TRUE);
                }
            }
        }
        return buildPlaylist(true, includeEvents, selectedEvents.keySet());
    }

    /** Creates Lista 3: every stable Premium channel, without events. */
    public Playlist toStablePlaylist() {
        return buildPlaylist(true, false, Collections.emptySet());
    }

    /**
     * Creates Lista 4 from the exact event identities selected by the user.
     * A stale or unknown selection simply produces no channel; it can never
     * turn an arbitrary value into a provider URL.
     */
    public Playlist toEventsPlaylist(Set<String> selectedEventIds) {
        return buildPlaylist(false, true, selectedEventIds);
    }

    private Playlist buildPlaylist(
            boolean includeStable,
            boolean includeEvents,
            Set<String> selectedEventIds
    ) {
        List<Channel> channels = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        Set<String> safeSelected = selectedEventIds == null
                ? Collections.emptySet()
                : selectedEventIds;
        for (Entry entry : entries) {
            boolean stable = entry.getType() == EntryType.STABLE_CHANNEL;
            boolean event = entry.getType() == EntryType.TEMPORARY_EVENT;
            if (entry.getType() == EntryType.UNSUPPORTED
                    || stable && !includeStable
                    || event && !includeEvents
                    || event && !safeSelected.contains(entry.getId())) {
                continue;
            }
            String identity = entry.getIdentity();
            if (identity.isBlank() || seen.put(identity, Boolean.TRUE) != null) continue;

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("tvg-id", "highfly-premium:" + identity);
            attributes.put("tvg-name", entry.getName());
            attributes.put("group-title", groupFor(entry));
            attributes.put("x-resolver", "highfly");
            if (stable) {
                // Stable entries may be reconstructed from the local metadata
                // snapshot. They are not virtual playback items.
                attributes.put("x-highfly-premium-stable", "true");
            } else {
                // Only temporary events are virtual: their signed stream is
                // intentionally resolved at selection time and never cached.
                attributes.put("x-highfly-premium", "true");
                attributes.put("x-highfly-premium-virtual", "true");
            }
            attributes.put("x-highfly-premium-id", entry.getId());
            attributes.put("x-highfly-premium-kind", entry.getType().getValue());
            attributes.put("x-highfly-premium-list", entry.getType() == EntryType.STABLE_CHANNEL
                    ? "3"
                    : "4");
            attributes.put("x-resolver-id", stable
                    ? entry.getSlug()
                    : eventResolverId(entry.getId()));

            URI streamUri = stable
                    ? URI.create("https://leaf.highfly.dev/m3u/"
                    + entry.getSlug() + "/live.m3u8")
                    : eventPlaceholder(entry.getId());
            channels.add(new Channel(
                    entry.getName(),
                    streamUri,
                    entry.getLogoUri(),
                    groupFor(entry),
                    attributes
            ));
        }
        return Playlist.withEpgUris(channels, Collections.emptyList());
    }

    private static String groupFor(Entry entry) {
        String category = entry.getCategory().isBlank() ? "Eventos" : entry.getCategory();
        return entry.getType() == EntryType.TEMPORARY_EVENT
                ? "Lista 4 · Eventos temporales · " + category
                : "Lista 3 · Highfly · " + category;
    }

    static String eventResolverId(String id) {
        return "event-" + sha256(id).substring(0, 24);
    }

    private static URI eventPlaceholder(String id) {
        return URI.create(EVENT_PLACEHOLDER_BASE + sha256(id).substring(0, 32) + ".m3u8");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            // SHA-256 is mandatory on Android. This fallback keeps the URI
            // deterministic even on a broken test/runtime provider.
            return Integer.toHexString(value == null ? 0 : value.hashCode())
                    .replace('-', '0');
        }
    }

    public enum EntryType {
        STABLE_CHANNEL("estable"),
        TEMPORARY_EVENT("evento"),
        UNSUPPORTED("no compatible");

        private final String value;

        EntryType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static final class Entry {
        private final String id;
        private final String slug;
        private final String name;
        private final String category;
        private final URI logoUri;
        private final String description;
        private final String releaseInfo;
        private final EntryType type;

        Entry(
                String id,
                String slug,
                String name,
                String category,
                URI logoUri,
                String description,
                String releaseInfo,
                EntryType type
        ) {
            this.id = id == null ? "" : id;
            this.slug = slug == null ? "" : slug;
            this.name = name == null || name.isBlank() ? this.id : name;
            this.category = category == null ? "" : category;
            this.logoUri = logoUri;
            this.description = description == null ? "" : description;
            this.releaseInfo = releaseInfo == null ? "" : releaseInfo;
            this.type = type == null ? EntryType.UNSUPPORTED : type;
        }

        public String getId() {
            return id;
        }

        public String getSlug() {
            return slug;
        }

        public String getName() {
            return name;
        }

        public String getCategory() {
            return category;
        }

        public URI getLogoUri() {
            return logoUri;
        }

        public String getDescription() {
            return description;
        }

        public String getReleaseInfo() {
            return releaseInfo;
        }

        public EntryType getType() {
            return type;
        }

        public String getIdentity() {
            return type == EntryType.STABLE_CHANNEL ? slug : id;
        }
    }
}
