package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allowlist of channels whose stream URL is resolved dynamically. */
public final class StreamResolverRegistry {
    private final List<StreamResolver> resolvers;
    private final ResolverCatalog catalog;
    private final ResolverPreferences preferences;
    private final Map<String, StreamResolver> resolversByProvider;

    public StreamResolverRegistry() {
        List<StreamResolver> configured = new ArrayList<>();
        configured.add(new TvnStreamResolver());
        configured.add(new MeganoticiasStreamResolver());
        resolvers = Collections.unmodifiableList(configured);
        catalog = null;
        preferences = null;
        resolversByProvider = Collections.emptyMap();
    }

    public StreamResolverRegistry(
            ResolverCatalog catalog,
            ResolverPreferences preferences
    ) {
        this.catalog = catalog;
        this.preferences = preferences;
        List<StreamResolver> configured = new ArrayList<>();
        Map<String, StreamResolver> byProvider = new LinkedHashMap<>();
        for (ResolverDefinition definition : catalog.getProviders()) {
            StreamResolver resolver = create(definition);
            configured.add(resolver);
            byProvider.put(definition.getId(), resolver);
        }
        resolvers = Collections.unmodifiableList(configured);
        resolversByProvider = Collections.unmodifiableMap(byProvider);
    }

    public StreamResolver find(Channel channel) {
        if (catalog != null) {
            ResolverDefinition definition = catalog.find(channel);
            if (definition == null || !preferences.isEnabled(definition)) return null;
            return resolversByProvider.get(definition.getId());
        }
        for (StreamResolver resolver : resolvers) {
            if (resolver.supports(channel)) return resolver;
        }
        return null;
    }

    public ResolverDefinition findDefinition(Channel channel) {
        return catalog == null ? null : catalog.find(channel);
    }

    public boolean isChannelEnabled(Channel channel) {
        ResolverDefinition definition = findDefinition(channel);
        return definition == null || preferences.isEnabled(definition);
    }

    public String getCatalogVersion() {
        return catalog == null ? "integrado" : catalog.getVersion();
    }

    public List<ResolverDefinition> getDefinitions() {
        return catalog == null ? Collections.emptyList() : catalog.getProviders();
    }

    public Map<String, Integer> countChannels(List<Channel> channels) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (catalog == null || channels == null) return result;
        for (ResolverDefinition definition : catalog.getProviders()) {
            result.put(definition.getId(), 0);
        }
        for (Channel channel : channels) {
            ResolverDefinition definition = catalog.find(channel);
            if (definition != null) {
                result.put(definition.getId(), result.get(definition.getId()) + 1);
            }
        }
        return result;
    }

    private static StreamResolver create(ResolverDefinition definition) {
        return switch (definition.getEngine()) {
            case "tvn" -> new TvnStreamResolver(definition);
            case "meganoticias" -> new MeganoticiasStreamResolver(definition);
            case "24horas" -> new TwentyFourHoursStreamResolver(definition);
            case "tvvoo" -> new TvVooStreamResolver(definition);
            case "highfly" -> new HighflyStreamResolver(definition);
            default -> throw new IllegalArgumentException("Motor de resolutor desconocido.");
        };
    }
}
