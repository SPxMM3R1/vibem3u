package cl.streambox.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stable playback metadata for one Highfly entry published by Lista M3U.
 *
 * <p>The published {@code catalogKey} remains the stable identity, while the
 * resource id and slug are only resolver references. No stream URL, session
 * parameter or authorization value is exposed by this model; the resolver
 * obtains a playable source only when the channel is opened.</p>
 */
public final class HighflyCatalogChannel {
    private final String resourceId;
    private final String slug;
    private final String stableId;
    private final String name;
    private final String group;
    private final String category;
    private final String logoUrl;
    private final List<String> genres;

    /** Creates a playback projection from a web-published stable identity. */
    public HighflyCatalogChannel(
            String resourceId,
            String stableId,
            String name,
            String group,
            String category,
            String logoUrl,
            List<String> genres
    ) {
        String normalizedResourceId = clean(resourceId).toLowerCase(Locale.ROOT);
        if (!normalizedResourceId.matches("leaf:[A-Za-z0-9_-]{2,128}")) {
            throw new IllegalArgumentException("ID Highfly inválido.");
        }
        this.resourceId = normalizedResourceId;
        this.slug = normalizedResourceId.substring("leaf:".length());
        this.name = nonBlank(name, this.slug);
        String publishedStableId = clean(stableId);
        if (!publishedStableId.matches("[A-Za-z][A-Za-z0-9._-]{1,127}")) {
            throw new IllegalArgumentException("Identidad pública Highfly inválida.");
        }
        this.stableId = publishedStableId;
        this.group = nonBlank(group, "Highfly");
        this.category = clean(category);
        this.logoUrl = safeLogoUrl(logoUrl);

        LinkedHashSet<String> genreValues = new LinkedHashSet<>();
        if (genres != null) {
            for (String value : genres) {
                String candidate = clean(value);
                if (!candidate.isEmpty()) genreValues.add(candidate);
            }
        }
        if (genreValues.isEmpty() && !this.category.isEmpty()) {
            genreValues.add(this.category);
        }
        this.genres = Collections.unmodifiableList(new ArrayList<>(genreValues));
    }

    public String getResourceId() { return resourceId; }
    public String getSlug() { return slug; }
    public String getStableId() { return stableId; }
    public String getName() { return name; }
    public String getGroup() { return group; }
    public String getCategory() { return category; }
    public String getLogoUrl() { return logoUrl; }
    public List<String> getGenres() { return genres; }

    /**
     * Returns the identity state required by the Lista M3U contract.
     * Web-published provisional identities remain provisional until the
     * Lista M3U runner confirms them.
     */
    public String getIdentityState() {
        return isCanonicalIdentity() ? "canonical" : "provisional";
    }

    public boolean isCanonicalIdentity() {
        return !stableId.startsWith("Highfly.");
    }

    /**
     * Returns the country suffix from a canonical ID such as
     * {@code SkySportsTennis.uk}. Provisional identities have no confirmed
     * country and therefore return an empty value.
     */
    public String getCountryKey() {
        if (!isCanonicalIdentity()) return "";
        int separator = stableId.lastIndexOf('.');
        if (separator <= 0 || separator >= stableId.length() - 1) return "";
        return stableId.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    /** Creates the tokenless playback reference for this published row. */
    public Channel toChannel() {
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("tvg-id", tvgId());
        attributes.put("tvg-name", name);
        if (!group.isEmpty()) attributes.put("group-title", group);
        if (!category.isEmpty()) attributes.put("x-highfly-category", category);
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-stable-id", stableId);
        attributes.put("x-resolver-id", slug);
        attributes.put("x-resolver-resource-id", resourceId);
        attributes.put("x-resolver-refresh", "on_play");
        URI reference = DynamicSourceReference.create("highfly", slug);
        if (reference == null) throw new IllegalStateException("Referencia Highfly inválida.");
        URI logo = logoUrl.isEmpty() ? null : URI.create(logoUrl);
        return new Channel(name, reference, logo, group, attributes);
    }

    private String tvgId() {
        return stableId;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        return clean(value).isEmpty() ? clean(fallback) : clean(value);
    }

    private static String safeLogoUrl(String value) {
        String candidate = clean(value);
        if (candidate.isEmpty()) return "";
        try {
            URI uri = URI.create(candidate);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "cdn.highfly.to".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    ? uri.toString()
                    : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
