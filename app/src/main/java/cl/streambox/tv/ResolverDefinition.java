package cl.streambox.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable, declarative configuration for one provider resolver. */
public final class ResolverDefinition {
    private final String id;
    private final String displayName;
    private final String engine;
    private final boolean enabledByDefault;
    private final long cacheTtlMillis;
    private final Set<String> tvgIds;
    private final List<String> tvgIdSuffixes;
    private final Set<String> hosts;
    private final Map<String, String> config;
    private final Map<String, List<String>> compatibilityAliases;

    ResolverDefinition(
            String id,
            String displayName,
            String engine,
            boolean enabledByDefault,
            long cacheTtlMillis,
            Set<String> tvgIds,
            List<String> tvgIdSuffixes,
            Set<String> hosts,
            Map<String, String> config,
            Map<String, List<String>> compatibilityAliases
    ) {
        this.id = id;
        this.displayName = displayName;
        this.engine = engine;
        this.enabledByDefault = enabledByDefault;
        this.cacheTtlMillis = Math.max(0L, cacheTtlMillis);
        this.tvgIds = immutableLowerSet(tvgIds);
        List<String> suffixes = new ArrayList<>();
        for (String suffix : tvgIdSuffixes) {
            if (suffix != null && !suffix.isBlank()) {
                suffixes.add(suffix.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.tvgIdSuffixes = Collections.unmodifiableList(suffixes);
        this.hosts = immutableLowerSet(hosts);
        this.config = Collections.unmodifiableMap(new LinkedHashMap<>(config));

        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : compatibilityAliases.entrySet()) {
            List<String> values = new ArrayList<>();
            for (String value : entry.getValue()) {
                if (value != null && !value.isBlank()) values.add(value.trim());
            }
            aliases.put(
                    entry.getKey().trim().toLowerCase(Locale.ROOT),
                    Collections.unmodifiableList(values)
            );
        }
        this.compatibilityAliases = Collections.unmodifiableMap(aliases);
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getEngine() { return engine; }
    public boolean isEnabledByDefault() { return enabledByDefault; }
    public long getCacheTtlMillis() { return cacheTtlMillis; }

    public String getConfig(String key, String fallback) {
        String value = config.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean getBooleanConfig(String key, boolean fallback) {
        String value = config.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public int getIntConfig(String key, int fallback, int minimum, int maximum) {
        String value = config.get(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(minimum, Math.min(maximum, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    boolean matchesExplicit(Channel channel) {
        String value = attribute(channel, "x-resolver");
        return !value.isBlank()
                && (id.equalsIgnoreCase(value) || engine.equalsIgnoreCase(value));
    }

    boolean matchesTvgId(Channel channel) {
        String tvgId = safe(channel == null ? null : channel.getTvgId())
                .toLowerCase(Locale.ROOT);
        if (tvgIds.contains(tvgId)) return true;
        for (String suffix : tvgIdSuffixes) {
            if (tvgId.endsWith(suffix)) return true;
        }
        return false;
    }

    boolean matchesHost(Channel channel) {
        if (channel == null || channel.getStreamUri() == null) return false;
        String host = safe(channel.getStreamUri().getHost()).toLowerCase(Locale.ROOT);
        return !host.isBlank() && hosts.contains(host);
    }

    public String stableSourceId(Channel channel) {
        String configured = attribute(channel, "x-resolver-id");
        if (!configured.isBlank()) return configured;
        String tvgId = safe(channel == null ? null : channel.getTvgId()).trim();
        if (!tvgId.isBlank()) return tvgId;
        return safe(channel == null ? null : channel.getName()).trim();
    }

    public List<String> resolverAliases(Channel channel) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String values = attribute(channel, "x-resolver-ids");
        if (!values.isBlank()) {
            for (String value : values.split(";")) {
                if (!value.isBlank()) result.add(value.trim());
            }
            // The list project controls this order. Do not append APK-side
            // compatibility aliases when the M3U already carries an explicit
            // resolver contract: probing unrelated aliases only delays the
            // first playable source and can select a different regional feed.
            return Collections.unmodifiableList(new ArrayList<>(result));
        }
        String single = attribute(channel, "x-resolver-id");
        if (!single.isBlank()) {
            return Collections.singletonList(single);
        }

        String tvgId = safe(channel == null ? null : channel.getTvgId())
                .trim()
                .toLowerCase(Locale.ROOT);
        List<String> compatibility = compatibilityAliases.get(tvgId);
        if (compatibility != null) result.addAll(compatibility);
        String channelName = safe(channel == null ? null : channel.getName())
                .trim()
                .toLowerCase(Locale.ROOT);
        compatibility = compatibilityAliases.get(channelName);
        if (compatibility != null) result.addAll(compatibility);
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    public String channelManifestUrl(Channel channel) {
        String manifest = attribute(channel, "x-resolver-manifest");
        return manifest.isBlank() ? getConfig("manifestUrl", "") : manifest;
    }

    /**
     * Returns whether the channel explicitly requests the data-only recipe
     * authorised by this provider definition.
     *
     * <p>The M3U cannot invent or enable a recipe on its own: the requested
     * identifier must exactly match both the APK capability and the trusted
     * resolver catalogue. Unknown or mismatched values therefore fail
     * closed and the resolver keeps its fixed parser/fallback path.</p>
     */
    public boolean usesRecipe(Channel channel, String supportedRecipe) {
        String requested = attribute(channel, "x-resolver-recipe");
        if (requested.isBlank() || supportedRecipe == null) return false;
        String authorised = getConfig("recipeId", "");
        return requested.equalsIgnoreCase(supportedRecipe)
                && requested.equalsIgnoreCase(authorised);
    }

    public String requestedRecipe(Channel channel) {
        return attribute(channel, "x-resolver-recipe");
    }

    private static String attribute(Channel channel, String key) {
        if (channel == null || channel.getAttributes() == null) return "";
        return safe(channel.getAttributes().get(key)).trim();
    }

    private static Set<String> immutableLowerSet(Iterable<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
