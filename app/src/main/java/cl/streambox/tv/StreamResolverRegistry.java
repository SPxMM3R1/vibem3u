package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Allowlist of channels whose stream URL is resolved dynamically. */
public final class StreamResolverRegistry {
    private final List<StreamResolver> resolvers;

    public StreamResolverRegistry() {
        List<StreamResolver> configured = new ArrayList<>();
        configured.add(new TvnStreamResolver());
        configured.add(new MeganoticiasStreamResolver());
        resolvers = Collections.unmodifiableList(configured);
    }

    public StreamResolver find(Channel channel) {
        for (StreamResolver resolver : resolvers) {
            if (resolver.supports(channel)) return resolver;
        }
        return null;
    }
}
