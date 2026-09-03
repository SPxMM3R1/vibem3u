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
        this(null);
    }

    /** Fallback registry that can still resolve virtual Premium channels. */
    public StreamResolverRegistry(HighflyPremiumCatalogRepository premiumCatalogRepository) {
        List<StreamResolver> configured = new ArrayList<>();
        configured.add(new TvnStreamResolver());
        configured.add(new MeganoticiasStreamResolver());
        if (premiumCatalogRepository != null) {
            configured.add(new HighflyStreamResolver(
                    ResolverDefinition.fallbackHighfly(),
                    premiumCatalogRepository
            ));
        }
        resolvers = Collections.unmodifiableList(configured);
        catalog = null;
        preferences = null;
        resolversByProvider = Collections.emptyMap();
    }

    public StreamResolverRegistry(
            ResolverCatalog catalog,
            ResolverPreferences preferences
    ) {
        this(catalog, preferences, null);
    }

    public StreamResolverRegistry(
            ResolverCatalog catalog,
            ResolverPreferences preferences,
            HighflyPremiumCatalogRepository premiumCatalogRepository
    ) {
        this.catalog = catalog;
        this.preferences = preferences;
        List<StreamResolver> configured = new ArrayList<>();
        Map<String, StreamResolver> byProvider = new LinkedHashMap<>();
        for (ResolverDefinition definition : catalog.getProviders()) {
            StreamResolver resolver = create(
                    definition,
                    preferences,
                    premiumCatalogRepository
            );
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

    public void clearSensitiveState() {
        for (StreamResolver resolver : resolvers) {
            resolver.clearSensitiveState();
        }
    }

    private static StreamResolver create(
            ResolverDefinition definition,
            ResolverPreferences preferences,
            HighflyPremiumCatalogRepository premiumCatalogRepository
    ) {
        return switch (definition.getEngine()) {
            case "tvn" -> new TvnStreamResolver(definition);
            case "meganoticias" -> new MeganoticiasStreamResolver(definition);
            case "tvvoo" -> new TvVooStreamResolver(
                    definition,
                    preferences.getTvVooResolutionMode()
            );
            case "highfly" -> new HighflyStreamResolver(
                    definition,
                    premiumCatalogRepository
            );
            case "vavoo" -> {
                if (!BuildConfig.ENABLE_EXPERIMENTAL_VAVOO) {
                    throw new IllegalArgumentException("Motor Vavoo no disponible.");
                }
                yield new VavooStreamResolver(definition);
            }
            default -> throw new IllegalArgumentException("Motor de resolutor desconocido.");
        };
    }
}
