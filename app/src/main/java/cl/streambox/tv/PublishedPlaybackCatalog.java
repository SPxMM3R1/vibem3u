package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only projection of Lista M3U's published playback configuration. */
final class PublishedPlaybackCatalog {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String LOGO_BASE =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/";

    private final List<Row> rows;
    private final List<String> excludedM3u;

    private PublishedPlaybackCatalog(List<Row> rows, List<String> excludedM3u) {
        List<Row> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparingInt(row -> row.order));
        this.rows = Collections.unmodifiableList(ordered);
        this.excludedM3u = Collections.unmodifiableList(new ArrayList<>(excludedM3u));
    }

    List<Row> getRows() { return rows; }
    List<String> excludedM3uIds() { return excludedM3u; }

    List<String> playbackOrder() {
        List<String> result = new ArrayList<>();
        for (Row row : rows) {
            if ("active".equals(row.state)) result.add(row.appKey());
        }
        return Collections.unmodifiableList(result);
    }

    boolean hasActiveProviderChannels() {
        for (Row row : rows) {
            if ("provider".equals(row.kind) && "active".equals(row.state)) return true;
        }
        return false;
    }

    List<Channel> activeProviderChannels() {
        List<Channel> result = new ArrayList<>();
        for (Row row : rows) {
            if (!"provider".equals(row.kind) || !"active".equals(row.state)) continue;
            try {
                result.add("tvvoo".equals(row.provider)
                        ? row.toTvVoo().toChannel()
                        : row.toHighfly().toChannel());
            } catch (IOException error) {
                throw new IllegalStateException(
                        "Validated provider row cannot be converted: " + row.stableId,
                        error
                );
            }
        }
        return Collections.unmodifiableList(result);
    }

    Map<String, Integer> activeNumbers() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Row row : rows) {
            if ("active".equals(row.state)) result.put(row.appKey(), row.number);
        }
        return Collections.unmodifiableMap(result);
    }

    static PublishedPlaybackCatalog empty() {
        return new PublishedPlaybackCatalog(Collections.emptyList(), Collections.emptyList());
    }

    boolean isEmpty() { return rows.isEmpty(); }

    static PublishedPlaybackCatalog parse(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) throw new IOException("Configuración web vacía.");
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("Diseño web demasiado grande.");
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schemaVersion", -1) != 1) {
                throw new IOException("Versión de la configuración web incompatible.");
            }
            JSONArray values = root.optJSONArray("channels");
            if (values == null || values.length() > 10000) {
                throw new IOException("La configuración web no contiene channels[] válido.");
            }
            JSONArray excludedValues = root.optJSONArray("excludedM3u");
            if (excludedValues != null && excludedValues.length() > 10000) {
                throw new IOException("Hay demasiadas exclusiones M3U.");
            }
            List<String> excludedM3u = new ArrayList<>();
            Set<String> excludedIds = new HashSet<>();
            if (excludedValues != null) {
                for (int index = 0; index < excludedValues.length(); index++) {
                    Object rawId = excludedValues.opt(index);
                    if (!(rawId instanceof String)) {
                        throw new IOException("excludedM3u solo acepta tvg-id de texto.");
                    }
                    String stableId = ((String) rawId).trim();
                    if (stableId.isEmpty() || stableId.length() > 512
                            || stableId.contains("://")
                            || stableId.matches("(?is).*\\.m3u8?(?:\\b|\\?).*|.*\\.mpd$|.*[\\r\\n].*")) {
                        throw new IOException("tvg-id no válido en excludedM3u.");
                    }
                    if (!excludedIds.add(stableId)) {
                        throw new IOException("Identidad M3U excluida duplicada: " + stableId);
                    }
                    excludedM3u.add(stableId);
                }
            }
            List<Row> rows = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            Set<Integer> orders = new HashSet<>();
            Set<Integer> activeNumbers = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) throw new IOException("Fila web no válida: " + index);
                Row row = Row.parse(value);
                if (!identities.add(row.stableId)) {
                    throw new IOException("Identidad repetida en la configuración web: " + row.stableId);
                }
                if (!orders.add(row.order)) {
                    throw new IOException("Posición repetida en la configuración web: " + row.order);
                }
                if ("active".equals(row.state) && !activeNumbers.add(row.number)) {
                    throw new IOException("Número repetido en la configuración web: " + row.number);
                }
                if ("active".equals(row.state) && "m3u".equals(row.kind)
                        && excludedIds.contains(row.stableId)) {
                    throw new IOException("Canal activo y excluido a la vez: " + row.stableId);
                }
                rows.add(row);
            }
            return new PublishedPlaybackCatalog(rows, excludedM3u);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Configuración web inválida: " + safeMessage(error), error);
        }
    }

    /** Orders playback from the web publication and filters its hidden/trash rows. */
    List<Channel> applyToPlayback(List<Channel> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        Map<String, Row> rowsByKey = new LinkedHashMap<>();
        for (Row row : rows) rowsByKey.put(row.appKey(), row);
        Set<String> excluded = new HashSet<>(excludedM3u);
        Map<String, List<Channel>> byKey = new LinkedHashMap<>();
        List<Channel> unlisted = new ArrayList<>();
        for (Channel channel : input) {
            if (channel == null) continue;
            String tvgId = channel.getTvgId() == null ? "" : channel.getTvgId().trim();
            if (excluded.contains(tvgId)) continue;
            String key = keyFor(channel);
            Row configured = rowsByKey.get(key);
            if (configured != null && !"active".equals(configured.state)) continue;
            if (configured == null || key.isEmpty()) {
                unlisted.add(channel);
            } else {
                byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(channel);
            }
        }

        List<Channel> result = new ArrayList<>(input.size());
        for (Row row : rows) {
            if (!"active".equals(row.state)) continue;
            List<Channel> matches = byKey.get(row.appKey());
            if (matches != null) {
                for (Channel channel : matches) result.add(withDisplayName(channel, row.displayName));
            }
        }
        for (Channel channel : unlisted) result.add(channel);
        return Collections.unmodifiableList(result);
    }

    private static Channel withDisplayName(Channel channel, String displayName) {
        if (channel == null || AppStrings.isBlank(displayName)
                || displayName.equals(channel.getName())) return channel;
        return new Channel(
                displayName,
                channel.getStreamUri(),
                channel.getLogoUri(),
                channel.getGroup(),
                channel.getAttributes()
        );
    }

    int numberFor(Channel channel, int fallback) {
        Row row = rowsByKey().get(keyFor(channel));
        return row != null && "active".equals(row.state) && row.number > 0
                ? row.number
                : fallback;
    }

    private Map<String, Row> rowsByKey() {
        Map<String, Row> result = new LinkedHashMap<>();
        for (Row row : rows) result.put(row.appKey(), row);
        return result;
    }

    private static String keyFor(Channel channel) {
        if (channel == null) return "";
        if (TvVooChannelMerge.isTvVoo(channel)) {
            String stableId = channel.getAttributes().get("x-resolver-stable-id");
            if (AppStrings.isBlank(stableId)) {
                String tvgId = channel.getTvgId();
                String suffix = "@TvVoo";
                stableId = tvgId != null && tvgId.endsWith(suffix)
                        ? tvgId.substring(0, tvgId.length() - suffix.length())
                        : tvgId;
            }
            return AppStrings.isBlank(stableId) ? "" : "tvvoo:" + stableId.trim();
        }
        if (HighflyChannelMerge.isHighfly(channel)) {
            String stableId = channel.getAttributes().get("x-resolver-stable-id");
            if (AppStrings.isBlank(stableId)) stableId = channel.getTvgId();
            return AppStrings.isBlank(stableId) ? "" : "highfly:" + stableId.trim();
        }
        String tvgId = channel.getTvgId();
        return AppStrings.isBlank(tvgId) ? "" : "m3u:tvg:" + tvgId.trim();
    }

    private static String logoUrl(String... candidates) throws IOException {
        for (String candidate : candidates) {
            String path = candidate == null ? "" : candidate.trim().replace('\\', '/');
            if (path.isEmpty()) continue;
            if (!path.startsWith("logos/")) return "";
            String[] parts = path.split("/", -1);
            for (String part : parts) {
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                    throw new IOException("Ruta de logo web inválida.");
                }
            }
            return LOGO_BASE + path;
        }
        return "";
    }

    private static String safeText(JSONObject value, String field, boolean required)
            throws IOException {
        String text = value.optString(field, "").trim();
        if (required && text.isEmpty()) throw new IOException("Falta " + field + " en el diseño web.");
        if (text.length() > 256 || text.matches("(?is).*https?://.*|.*\\.m3u8?(?:\\b|\\?).*"
                + "|.*(?:access_token|token|signature|hdnts)=.*")) {
            throw new IOException("Metadato web no permitido: " + field);
        }
        return text;
    }

    private static List<String> stringArray(JSONObject value, String field) throws IOException {
        JSONArray items = value.optJSONArray(field);
        if (items == null) return Collections.emptyList();
        if (items.length() > 128) throw new IOException("Demasiados aliases en " + field);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            String item = items.optString(index, "").trim();
            if (item.isEmpty() || item.length() > 256
                    || item.matches("(?is).*https?://.*|.*\\.m3u8?(?:\\b|\\?).*"
                    + "|.*(?:access_token|token|signature|hdnts)=.*")) {
                throw new IOException("Alias no permitido en " + field);
            }
            if (!result.contains(item)) result.add(item);
        }
        return result;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "error desconocido" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "error desconocido" : message;
    }

    static final class Row {
        final String kind;
        final String provider;
        final String stableId;
        final String state;
        final String name;
        final String displayName;
        final String group;
        final String category;
        final String country;
        final String alias;
        final String resourceId;
        final String resolverSlug;
        final String identityState;
        final List<String> aliases;
        final String logo;
        final int order;
        final int number;

        private Row(
                String kind,
                String provider,
                String stableId,
                String state,
                String name,
                String displayName,
                String group,
                String category,
                String country,
                String alias,
                String resourceId,
                String resolverSlug,
                String identityState,
                List<String> aliases,
                String logo,
                int order,
                int number
        ) {
            this.kind = kind;
            this.provider = provider;
            this.stableId = stableId;
            this.state = state;
            this.name = name;
            this.displayName = displayName;
            this.group = group;
            this.category = category;
            this.country = country;
            this.alias = alias;
            this.resourceId = resourceId;
            this.resolverSlug = resolverSlug;
            this.identityState = identityState;
            this.aliases = aliases;
            this.logo = logo;
            this.order = order;
            this.number = number;
        }

        static Row parse(JSONObject value) throws IOException {
            String kind = value.optString("kind", "").trim();
            String state = value.optString("state", "").trim();
            if (!kind.equals("m3u") && !kind.equals("provider")) {
                throw new IOException("Tipo de fila web no permitido.");
            }
            if (!state.equals("active") && !state.equals("hidden") && !state.equals("deleted")) {
                throw new IOException("Estado de fila web no permitido.");
            }
            int order = value.optInt("order", -1);
            int number = value.optInt("number", -1);
            if (order < 1 || order > 1_000_000 || number < 1 || number > 1_000_000) {
                throw new IOException("Orden o número web no válido.");
            }
            String provider = "";
            String stableId;
            String alias = "";
            String resourceId = "";
            String resolverSlug = "";
            String identityState = "";
            List<String> aliases = Collections.emptyList();
            if (kind.equals("m3u")) {
                stableId = safeText(value, "tvgId", true);
                if (stableId.contains("://") || stableId.startsWith("leaf:")) {
                    throw new IOException("tvg-id web no es una identidad pública estable.");
                }
            } else {
                provider = value.optString("provider", "").trim().toLowerCase(java.util.Locale.ROOT);
                if (!provider.equals("highfly") && !provider.equals("tvvoo")) {
                    throw new IOException("Proveedor web no permitido.");
                }
                stableId = safeText(value, "catalogKey", true);
                resourceId = safeText(value, "providerResourceId", true);
                nameRequired(value);
                if (provider.equals("highfly")) {
                    resolverSlug = safeText(value, "resolverSlug", true);
                    if (stableId.startsWith("leaf:") || stableId.contains("://")
                            || !resourceId.matches("leaf:[A-Za-z0-9_-]{2,128}")
                            || !resourceId.substring(5).equals(resolverSlug)) {
                        throw new IOException("Identidad y referencia Highfly no coinciden.");
                    }
                } else {
                    alias = safeText(value, "alias", false);
                    if (alias.isEmpty() && stableId.contains("|")) {
                        alias = stableId.substring(stableId.indexOf('|') + 1);
                    }
                    if (!resourceId.equals(stableId) || !TvVooCatalogChannel.isStableId(stableId)) {
                        throw new IOException("TvVoo debe usar catalogKey como identidad estable.");
                    }
                    identityState = "canonical";
                    aliases = stringArray(value, "resolverAliases");
                    if (aliases.isEmpty()) aliases = stringArray(value, "aliases");
                }
                if (provider.equals("highfly")) {
                    identityState = safeText(value, "identityState", true);
                    if (!identityState.equals("canonical") && !identityState.equals("provisional")) {
                        throw new IOException("identityState Highfly no permitido.");
                    }
                }
            }
            String name = safeText(value, "name", true);
            String displayName = safeText(value, "displayName", false);
            if (displayName.length() > 160 || displayName.matches("(?s).*[\\r\\n].*")) {
                throw new IOException("Nombre visible web no permitido.");
            }
            String group = safeText(value, "group", kind.equals("provider"));
            String category = safeText(value, "category", false);
            String country = safeText(value, "country", false);
            if (kind.equals("provider") && group.isEmpty()) group = category;
            String logo = logoUrl(
                    value.optString("logoOverride", ""),
                    value.optString("logoPath", "")
            );
            return new Row(
                    kind, provider, stableId, state, name, displayName, group, category, country,
                    alias, resourceId, resolverSlug, identityState, aliases, logo, order, number
            );
        }

        private static void nameRequired(JSONObject value) throws IOException {
            safeText(value, "name", true);
        }

        String identityKey() { return kind + ":" + stableId; }

        String appKey() {
            if (kind.equals("m3u")) return "m3u:tvg:" + stableId;
            return (provider.equals("tvvoo") ? "tvvoo:" : "highfly:") + stableId;
        }

        TvVooCatalogChannel toTvVoo() throws IOException {
            String countryKey = country.isEmpty() ? stableId.substring(0, stableId.indexOf('|')) : country;
            try {
                TvVooCatalogChannel channel = new TvVooCatalogChannel(
                        stableId, alias, name, countryKey, group, category, logo, aliases
                );
                if (!stableId.equals(channel.getStableId())) {
                    throw new IOException("La identidad TvVoo fue normalizada de forma inesperada.");
                }
                return channel;
            } catch (IllegalArgumentException error) {
                throw new IOException("Fila TvVoo no válida: " + stableId, error);
            }
        }

        HighflyCatalogChannel toHighfly() throws IOException {
            try {
                HighflyCatalogChannel channel = new HighflyCatalogChannel(
                        resourceId, stableId, name, group, category, logo, Collections.emptyList()
                );
                if (!resolverSlug.equals(channel.getSlug())
                        || !identityState.equals(channel.getIdentityState())) {
                    throw new IOException("Identidad/ref. Highfly no coincide con el contrato.");
                }
                return channel;
            } catch (IllegalArgumentException error) {
                throw new IOException("Fila Highfly no válida: " + stableId, error);
            }
        }
    }
}
