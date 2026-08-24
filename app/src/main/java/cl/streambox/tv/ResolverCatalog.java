package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Strictly validated resolver catalogue. It contains data, never executable code. */
public final class ResolverCatalog {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_CATALOG_BYTES = 256 * 1024;
    private static final int MAX_PROVIDERS = 16;
    private static final int MAX_MATCH_VALUES = 128;
    private static final int MAX_ALIASES_PER_CHANNEL = 12;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Set<String> ENGINES = setOf(
            "tvn", "meganoticias", "24horas", "tvvoo", "highfly"
    );
    private static final Map<String, Set<String>> ALLOWED_CONFIG_HOSTS = allowedHosts();

    private final String version;
    private final List<ResolverDefinition> providers;
    private final Map<String, ResolverDefinition> providersById;

    private ResolverCatalog(String version, List<ResolverDefinition> providers) {
        this.version = version;
        this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
        Map<String, ResolverDefinition> byId = new LinkedHashMap<>();
        for (ResolverDefinition provider : providers) byId.put(provider.getId(), provider);
        providersById = Collections.unmodifiableMap(byId);
    }

    public String getVersion() { return version; }
    public List<ResolverDefinition> getProviders() { return providers; }
    public ResolverDefinition getById(String id) {
        return id == null ? null : providersById.get(id.toLowerCase(Locale.ROOT));
    }

    public ResolverDefinition find(Channel channel) {
        if (channel == null) return null;
        String explicit = channel.getAttributes().get("x-resolver");
        if (explicit != null && !explicit.isBlank()) {
            for (ResolverDefinition provider : providers) {
                if (provider.matchesExplicit(channel)) return provider;
            }
            return null;
        }
        for (ResolverDefinition provider : providers) {
            if (provider.matchesTvgId(channel)) return provider;
        }
        for (ResolverDefinition provider : providers) {
            if (provider.matchesHost(channel)) return provider;
        }
        return null;
    }

    public static ResolverCatalog parse(String json) throws IOException {
        if (json == null || json.isBlank()) throw new IOException("Catálogo vacío.");
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CATALOG_BYTES) {
            throw new IOException("Catálogo demasiado grande.");
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
                throw new IOException("Versión de esquema incompatible.");
            }
            String version = requiredString(root, "catalogVersion", 64);
            JSONArray array = root.getJSONArray("providers");
            if (array.length() < 1 || array.length() > MAX_PROVIDERS) {
                throw new IOException("Cantidad de resolutores inválida.");
            }
            List<ResolverDefinition> definitions = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                ResolverDefinition definition = parseDefinition(array.getJSONObject(index));
                if (!ids.add(definition.getId())) {
                    throw new IOException("Resolutor duplicado.");
                }
                definitions.add(definition);
            }
            return new ResolverCatalog(version, definitions);
        } catch (JSONException error) {
            throw new IOException("Catálogo JSON inválido.", error);
        }
    }

    private static ResolverDefinition parseDefinition(JSONObject object)
            throws JSONException, IOException {
        String id = requiredString(object, "id", 32).toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(id).matches()) throw new IOException("ID de resolutor inválido.");
        String displayName = requiredString(object, "name", 64);
        String engine = requiredString(object, "engine", 32).toLowerCase(Locale.ROOT);
        if (!ENGINES.contains(engine)) throw new IOException("Motor de resolutor desconocido.");
        boolean enabled = object.optBoolean("enabledByDefault", true);
        long ttlSeconds = Math.max(0L, Math.min(3600L, object.optLong("cacheTtlSeconds", 0L)));

        JSONObject match = object.optJSONObject("match");
        Set<String> tvgIds = strings(match, "tvgIds");
        List<String> suffixes = new ArrayList<>(strings(match, "tvgIdSuffixes"));
        Set<String> hosts = strings(match, "hosts");

        Map<String, String> config = stringObject(object.optJSONObject("config"), 64, 2048);
        validateConfig(engine, config);
        Map<String, List<String>> aliases = aliasesObject(
                object.optJSONObject("compatibilityAliases")
        );
        return new ResolverDefinition(
                id,
                displayName,
                engine,
                enabled,
                ttlSeconds * 1000L,
                tvgIds,
                suffixes,
                hosts,
                config,
                aliases
        );
    }

    private static Set<String> strings(JSONObject object, String key)
            throws JSONException, IOException {
        if (object == null || !object.has(key)) return Collections.emptySet();
        JSONArray array = object.getJSONArray(key);
        if (array.length() > MAX_MATCH_VALUES) throw new IOException("Demasiadas coincidencias.");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.getString(index).trim();
            if (value.length() > 160) throw new IOException("Coincidencia demasiado larga.");
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }

    private static Map<String, String> stringObject(
            JSONObject object,
            int maxEntries,
            int maxValueLength
    ) throws JSONException, IOException {
        if (object == null) return Collections.emptyMap();
        if (object.length() > maxEntries) throw new IOException("Configuración demasiado grande.");
        Map<String, String> values = new LinkedHashMap<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            String key = keys.next();
            Object raw = object.get(key);
            if (!(raw instanceof String) && !(raw instanceof Number)
                    && !(raw instanceof Boolean)) {
                throw new IOException("Valor de configuración inválido.");
            }
            String value = String.valueOf(raw).trim();
            if (key.length() > 64 || value.length() > maxValueLength) {
                throw new IOException("Configuración demasiado larga.");
            }
            values.put(key, value);
        }
        return values;
    }

    private static Map<String, List<String>> aliasesObject(JSONObject object)
            throws JSONException, IOException {
        if (object == null) return Collections.emptyMap();
        if (object.length() > MAX_MATCH_VALUES) throw new IOException("Demasiados aliases.");
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONArray array = object.getJSONArray(key);
            if (array.length() > MAX_ALIASES_PER_CHANNEL) {
                throw new IOException("Demasiados aliases para un canal.");
            }
            List<String> aliases = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                String alias = array.getString(index).trim();
                if (alias.length() > 240) throw new IOException("Alias demasiado largo.");
                if (!alias.isBlank()) aliases.add(alias);
            }
            result.put(key, aliases);
        }
        return result;
    }

    private static void validateConfig(String engine, Map<String, String> config)
            throws IOException {
        Set<String> allowedHosts = ALLOWED_CONFIG_HOSTS.get(engine);
        if (allowedHosts == null) allowedHosts = Collections.emptySet();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.toLowerCase(Locale.ROOT).contains("pattern")) {
                validatePattern(value);
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if ((lowerKey.endsWith("path") || lowerKey.endsWith("field"))
                    && !value.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){0,7}")) {
                throw new IOException("Ruta de datos no permitida.");
            }
            boolean networkValue = lowerKey.endsWith("url")
                    || lowerKey.endsWith("base")
                    || lowerKey.endsWith("template")
                    || lowerKey.endsWith("origin")
                    || lowerKey.endsWith("referer");
            if (!networkValue || value.isBlank()) continue;
            String sample = value
                    .replace("{id}", "sample")
                    .replace("{alias}", "sample")
                    .replace("{streamId}", "sample");
            URI uri;
            try {
                uri = URI.create(sample);
            } catch (IllegalArgumentException error) {
                throw new IOException("URL de resolutor inválida.", error);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw new IOException("Host de resolutor no permitido.");
            }
            if ("raw.githubusercontent.com".equalsIgnoreCase(uri.getHost())
                    && !uri.getPath().startsWith("/SPxMM3R1/lista-m3u/")) {
                throw new IOException("Ruta de catálogo no permitida.");
            }
        }
    }

    private static void validatePattern(String value) throws IOException {
        if (value.length() > 512) throw new IOException("Patrón demasiado largo.");
        // The remote catalogue is data-only. Disallow constructs most often
        // used for catastrophic backtracking or non-local regex behaviour.
        if (value.contains("(?")
                || value.matches(".*\\\\[1-9].*")
                || value.matches(".*\\([^)]*[+*][^)]*\\)[+*{].*")) {
            throw new IOException("Patrón no permitido.");
        }
        try {
            Pattern.compile(value);
        } catch (PatternSyntaxException error) {
            throw new IOException("Patrón inválido.", error);
        }
    }

    private static String requiredString(JSONObject object, String key, int maxLength)
            throws JSONException, IOException {
        String value = object.getString(key).trim();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IOException("Campo de catálogo inválido.");
        }
        return value;
    }

    private static Map<String, Set<String>> allowedHosts() {
        Map<String, Set<String>> result = new HashMap<>();
        result.put("tvn", setOf("live.tvn.cl", "www.tvn.cl", "mdstrm.com"));
        result.put("meganoticias", setOf(
                "www.meganoticias.cl", "meganoticias.cl", "api.mega.cl", "mdstrm.com"
        ));
        result.put("24horas", setOf("www.24horas.cl", "24horas.cl", "mdstrm.com"));
        result.put("tvvoo", setOf("tvvoo.hayd.uk"));
        result.put("highfly", setOf(
                "sports.highfly.dev", "leaf.highfly.dev", "raw.githubusercontent.com"
        ));
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> setOf(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }
}
