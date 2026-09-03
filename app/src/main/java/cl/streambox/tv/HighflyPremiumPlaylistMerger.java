package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Flattens ordinary sources, public stable Lista 3 and virtual Lista 4 events. */
final class HighflyPremiumPlaylistMerger {
    private HighflyPremiumPlaylistMerger() {}

    static List<Channel> merge(
            Map<Integer, Playlist> playlists,
            int stableSourcePosition,
            int eventSourcePosition
    ) {
        List<Channel> baseChannels = new ArrayList<>();
        Playlist stablePlaylist = playlists == null
                ? null
                : playlists.get(stableSourcePosition);
        Playlist eventPlaylist = playlists == null
                ? null
                : playlists.get(eventSourcePosition);

        if (playlists != null) {
            List<Map.Entry<Integer, Playlist>> ordered = new ArrayList<>(playlists.entrySet());
            Collections.sort(ordered, (left, right) ->
                    Integer.compare(left.getKey(), right.getKey()));
            for (Map.Entry<Integer, Playlist> entry : ordered) {
                int position = entry.getKey();
                if (position == stableSourcePosition || position == eventSourcePosition) continue;
                Playlist playlist = entry.getValue();
                if (playlist != null) baseChannels.addAll(playlist.getChannels());
            }
        }

        if (stablePlaylist != null && !stablePlaylist.getChannels().isEmpty()) {
            baseChannels = mergeStablePremiumSlots(
                    baseChannels,
                    stablePlaylist.getChannels()
            );
        }
        if (eventPlaylist != null && !eventPlaylist.getChannels().isEmpty()) {
            baseChannels.addAll(eventPlaylist.getChannels());
        }
        return baseChannels;
    }

    static List<Channel> mergeStablePremiumSlots(
            List<Channel> baseChannels,
            List<Channel> premiumChannels
    ) {
        Map<String, Channel> premiumByKey = new LinkedHashMap<>();
        if (premiumChannels != null) {
            for (Channel premium : premiumChannels) {
                String key = stableChannelKey(premium);
                if (!key.isBlank() && !premiumByKey.containsKey(key)) {
                    premiumByKey.put(key, premium);
                }
            }
        }

        List<Channel> merged = new ArrayList<>();
        Set<String> consumedPremiumKeys = new HashSet<>();
        if (baseChannels != null) {
            for (Channel base : baseChannels) {
                String key = isHighflyChannel(base) ? stableChannelKey(base) : "";
                Channel premium = key.isBlank() ? null : premiumByKey.get(key);
                if (premium != null && consumedPremiumKeys.add(key)) {
                    merged.add(withOriginalEpgIdentity(premium, base));
                } else {
                    merged.add(base);
                }
            }
        }

        // New stable entries have no old M3U slot. Keep them in catalog order
        // after the ordinary sources and before Lista 4 events.
        for (Map.Entry<String, Channel> entry : premiumByKey.entrySet()) {
            if (!consumedPremiumKeys.contains(entry.getKey())) {
                merged.add(entry.getValue());
            }
        }
        return merged;
    }

    private static boolean isHighflyChannel(Channel channel) {
        if (channel == null) return false;
        return "highfly".equalsIgnoreCase(
                channel.getAttributes().get("x-resolver")
        );
    }

    private static String stableChannelKey(Channel channel) {
        if (channel == null || channel.getAttributes() == null) return "";
        String value = channel.getAttributes().get("x-resolver-id");
        if (value == null || value.isBlank()) value = channel.getTvgId();
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Retain the original tvg-id so an existing EPG entry still matches. */
    private static Channel withOriginalEpgIdentity(Channel premium, Channel original) {
        if (premium == null || original == null || original.getTvgId().isBlank()) {
            return premium;
        }
        Map<String, String> attributes = new LinkedHashMap<>(premium.getAttributes());
        attributes.put("tvg-id", original.getTvgId());
        String originalTvgName = original.getAttributes().get("tvg-name");
        if (originalTvgName != null && !originalTvgName.isBlank()) {
            attributes.put("tvg-name", originalTvgName);
        }
        return new Channel(
                premium.getName(),
                premium.getStreamUri(),
                premium.getLogoUri() == null ? original.getLogoUri() : premium.getLogoUri(),
                premium.getGroup(),
                attributes
        );
    }
}
