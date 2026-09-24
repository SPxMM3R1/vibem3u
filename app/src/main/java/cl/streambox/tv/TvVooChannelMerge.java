package cl.streambox.tv;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Merges web-published TvVoo rows with ordered M3U sources. */
public final class TvVooChannelMerge {
    private TvVooChannelMerge() {}

    /** Appends published rows not already present and enriches matching M3U rows. */
    public static List<Channel> merge(
            List<Channel> playlistChannels,
            List<Channel> publishedChannels
    ) {
        if (publishedChannels == null || publishedChannels.isEmpty()) {
            return copy(playlistChannels);
        }
        List<Channel> result = new ArrayList<>();
        Map<String, Channel> publishedByKey = new HashMap<>();
        for (Channel published : publishedChannels) {
            if (!isTvVoo(published)) continue;
            for (String key : identityKeys(published)) {
                publishedByKey.putIfAbsent(key, published);
            }
        }

        Set<String> playlistKeys = new HashSet<>();
        Set<String> emittedExactAliases = new HashSet<>();
        if (playlistChannels != null) {
            for (Channel playlist : playlistChannels) {
                if (playlist == null) continue;
                if (isTvVoo(playlist)) {
                    Set<String> keys = identityKeys(playlist);
                    // Duplicate resolver identity across M3U1/M3U2 is removed
                    // using stable tvg-id or alias+country. Names never
                    // participate.
                    Set<String> exactKeys = exactDedupKeys(keys);
                    boolean duplicate = false;
                    for (String key : exactKeys) {
                        if (emittedExactAliases.contains(key)) duplicate = true;
                    }
                    // Add keys only after deciding to keep this row; a
                    // discarded row must not poison later duplicate checks.
                    if (duplicate && !exactKeys.isEmpty()) continue;
                    emittedExactAliases.addAll(exactKeys);
                    playlistKeys.addAll(keys);
                    Channel published = findByKeys(keys, publishedByKey);
                    result.add(enrichLogo(playlist, published));
                } else {
                    result.add(playlist);
                }
            }
        }

        Set<String> appended = new HashSet<>();
        for (Channel published : publishedChannels) {
            if (!isTvVoo(published)) continue;
            Set<String> keys = identityKeys(published);
            if (!Collections.disjoint(keys, playlistKeys)) continue;
            Set<String> exactKeys = exactDedupKeys(keys);
            if (!exactKeys.isEmpty() && !Collections.disjoint(exactKeys, appended)) continue;
            if (!exactKeys.isEmpty()) appended.addAll(exactKeys);
            result.add(published);
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean isTvVoo(Channel channel) {
        if (channel == null) return false;
        String resolver = channel.getAttributes().get("x-resolver");
        URI uri = channel.getStreamUri();
        return "tvvoo".equalsIgnoreCase(resolver)
                || (uri != null && "tvvoo".equalsIgnoreCase(uri.getScheme()))
                || channel.getTvgId().toLowerCase(Locale.ROOT).endsWith("@tvvoo");
    }

    /** Exact identity keys used for matching; display names are excluded. */
    static Set<String> identityKeys(Channel channel) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (channel == null) return result;
        String tvgId = channel.getTvgId();
        if (!AppStrings.isBlank(tvgId)) result.add("id:" + tvgId.trim());
        String country = countryOf(channel);
        for (String alias : aliasesOf(channel)) {
            String canonical = TvVooSourceHistory.canonicalAlias(alias);
            if (canonical.isEmpty()) continue;
            String aliasCountry = country;
            if (AppStrings.isBlank(aliasCountry)) aliasCountry = countryFromAlias(canonical);
            if (!AppStrings.isBlank(aliasCountry)) {
                result.add("alias:" + TvVooCatalogChannel.countryKey(aliasCountry)
                        + "|" + canonical);
            }
        }
        return result;
    }

    private static Set<String> exactDedupKeys(Set<String> keys) {
        Set<String> result = new LinkedHashSet<>();
        for (String key : keys) {
            if (key.startsWith("id:") || key.startsWith("alias:")) result.add(key);
        }
        return result;
    }

    private static List<Channel> copy(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(channels));
    }

    private static Channel findByKeys(Set<String> keys, Map<String, Channel> byKey) {
        for (String key : keys) {
            Channel match = byKey.get(key);
            if (match != null) return match;
        }
        return null;
    }

    private static Channel enrichLogo(Channel playlist, Channel published) {
        if (playlist == null || published == null || playlist.getLogoUri() != null
                || published.getLogoUri() == null) return playlist;
        return new Channel(
                playlist.getName(),
                playlist.getStreamUri(),
                published.getLogoUri(),
                playlist.getGroup(),
                playlist.getAttributes()
        );
    }

    private static List<String> aliasesOf(Channel channel) {
        List<String> result = new ArrayList<>();
        String values = channel.getAttributes().get("x-resolver-ids");
        if (!AppStrings.isBlank(values)) {
            for (String value : values.split(";")) {
                if (!AppStrings.isBlank(value)) result.add(value.trim());
            }
        }
        String single = channel.getAttributes().get("x-resolver-id");
        if (!AppStrings.isBlank(single)) {
            addIdentityAliases(single, result);
        }
        return result;
    }

    private static void addIdentityAliases(String value, List<String> result) {
        String direct = TvVooSourceHistory.canonicalAlias(value);
        if (!direct.isEmpty()) {
            result.add(direct);
            return;
        }
        String decoded = decodeOnce(value);
        for (String part : decoded.split("[|]")) {
            String canonical = TvVooSourceHistory.canonicalAlias(part);
            if (!canonical.isEmpty()) result.add(canonical);
        }
    }

    private static String countryOf(Channel channel) {
        String value = channel.getAttributes().get("x-resolver-country");
        if (AppStrings.isBlank(value)) value = channel.getAttributes().get("tvg-country");
        return AppStrings.isBlank(value) ? "" : value.trim();
    }

    private static String countryFromAlias(String alias) {
        String decoded = decodeOnce(alias);
        String marker = "|group:";
        String lower = decoded.toLowerCase(Locale.ROOT);
        int start = lower.indexOf(marker);
        if (start < 0) return "";
        String country = decoded.substring(start + marker.length());
        int end = country.indexOf('|');
        return end < 0 ? country : country.substring(0, end);
    }

    private static String decodeOnce(String value) {
        try {
            return URLDecoder.decode(
                    value.replace("+", "%2B"),
                    StandardCharsets.UTF_8.name()
            );
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }
}
