package cl.streambox.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Merges Highfly catalogue selections with the ordered M3U sources. */
public final class HighflyChannelMerge {
    private HighflyChannelMerge() {}

    /** M3U rows keep their name/logo/position; local-only rows are appended. */
    public static List<Channel> merge(
            List<Channel> playlistChannels,
            List<Channel> selectedChannels,
            boolean selectionEnabled
    ) {
        if (!selectionEnabled || selectedChannels == null || selectedChannels.isEmpty()) {
            return copy(playlistChannels);
        }

        Map<String, Channel> selectedByKey = new java.util.HashMap<>();
        for (Channel selected : selectedChannels) {
            if (!isHighfly(selected)) continue;
            for (String key : identityKeys(selected)) selectedByKey.putIfAbsent(key, selected);
        }

        List<Channel> result = new ArrayList<>();
        Set<String> playlistKeys = new HashSet<>();
        Set<String> emitted = new HashSet<>();
        if (playlistChannels != null) {
            for (Channel playlist : playlistChannels) {
                if (playlist == null) continue;
                if (!isHighfly(playlist)) {
                    result.add(playlist);
                    continue;
                }
                Set<String> keys = identityKeys(playlist);
                Set<String> exact = new LinkedHashSet<>(keys);
                exact.removeIf(key -> !key.startsWith("id:") && !key.startsWith("slug:"));
                boolean duplicate = false;
                for (String key : exact) if (emitted.contains(key)) duplicate = true;
                if (duplicate && !exact.isEmpty()) continue;
                emitted.addAll(exact);
                playlistKeys.addAll(keys);
                result.add(enrichLogo(playlist, findByKeys(keys, selectedByKey)));
            }
        }

        Set<String> appended = new HashSet<>();
        for (Channel selected : selectedChannels) {
            if (!isHighfly(selected)) continue;
            Set<String> keys = identityKeys(selected);
            if (!Collections.disjoint(keys, playlistKeys)) continue;
            Set<String> exact = new LinkedHashSet<>(keys);
            exact.removeIf(key -> !key.startsWith("id:") && !key.startsWith("slug:"));
            if (!exact.isEmpty() && !Collections.disjoint(exact, appended)) continue;
            appended.addAll(exact);
            result.add(selected);
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean isHighfly(Channel channel) {
        if (channel == null) return false;
        String resolver = channel.getAttributes().get("x-resolver");
        URI uri = channel.getStreamUri();
        String tvgId = channel.getTvgId().toLowerCase(Locale.ROOT);
        return "highfly".equalsIgnoreCase(resolver)
                || (uri != null && "highfly".equalsIgnoreCase(DynamicSourceReference.provider(uri)))
                || tvgId.startsWith("highfly.");
    }

    static Set<String> identityKeys(Channel channel) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (channel == null) return result;
        String tvgId = channel.getTvgId();
        if (!AppStrings.isBlank(tvgId)) result.add("id:" + tvgId.trim().toLowerCase(Locale.ROOT));
        String slug = channel.getAttributes().get("x-resolver-id");
        if (AppStrings.isBlank(slug) && channel.getStreamUri() != null
                && DynamicSourceReference.isAppOnly(channel.getStreamUri())
                && "highfly".equalsIgnoreCase(DynamicSourceReference.provider(channel.getStreamUri()))) {
            slug = DynamicSourceReference.stableId(channel.getStreamUri());
        }
        if (!AppStrings.isBlank(slug)
                && slug.matches("[A-Za-z0-9_-]{2,128}")) {
            result.add("slug:" + slug.toLowerCase(Locale.ROOT));
        }
        String resourceId = channel.getAttributes().get("x-resolver-resource-id");
        if (!AppStrings.isBlank(resourceId)) result.add("id:" + resourceId.toLowerCase(Locale.ROOT));
        return result;
    }

    private static Channel findByKeys(Set<String> keys, Map<String, Channel> byKey) {
        for (String key : keys) {
            Channel match = byKey.get(key);
            if (match != null) return match;
        }
        return null;
    }

    private static Channel enrichLogo(Channel playlist, Channel selected) {
        if (playlist == null || selected == null || playlist.getLogoUri() != null
                || selected.getLogoUri() == null) return playlist;
        return new Channel(
                playlist.getName(),
                playlist.getStreamUri(),
                selected.getLogoUri(),
                playlist.getGroup(),
                playlist.getAttributes()
        );
    }

    private static List<Channel> copy(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(channels));
    }
}
