package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class ResolverCatalogTest {
    @Test
    public void explicitResolverWinsBeforeTvgIdAndHost() throws Exception {
        ResolverCatalog catalog = ResolverCatalog.parse(catalogJson());
        Channel channel = channel(
                "0104",
                "https://leaf.highfly.dev/m3u/test/live.m3u8",
                attributes("x-resolver", "tvvoo")
        );

        assertEquals("tvvoo", catalog.find(channel).getId());
    }

    @Test
    public void exactSuffixAndHostMatchesRemainIndependent() throws Exception {
        ResolverCatalog catalog = ResolverCatalog.parse(catalogJson());

        assertEquals("tvn", catalog.find(channel(
                "0104", "https://example.org/tvn.m3u8", attributes()
        )).getId());
        assertEquals("tvvoo", catalog.find(channel(
                "Sport.uk@TvVoo", "https://example.org/sport.m3u8", attributes()
        )).getId());
        assertEquals("highfly", catalog.find(channel(
                "direct", "https://leaf.highfly.dev/m3u/test/live.m3u8", attributes()
        )).getId());
        assertNull(catalog.find(channel(
                "direct", "https://example.org/direct.m3u8", attributes()
        )));
    }

    @Test
    public void unknownExplicitResolverDoesNotFallThroughToAnotherProvider() throws Exception {
        ResolverCatalog catalog = ResolverCatalog.parse(catalogJson());
        assertNull(catalog.find(channel(
                "0104",
                "https://leaf.highfly.dev/m3u/test/live.m3u8",
                attributes("x-resolver", "unknown")
        )));
    }

    @Test
    public void rejectsExecutableOrUnapprovedNetworkConfiguration() {
        String invalid = catalogJson().replace(
                "https://tvvoo.hayd.uk/stream/tv",
                "https://attacker.invalid/collect"
        );
        assertThrows(IOException.class, () -> ResolverCatalog.parse(invalid));
    }

    @Test
    public void acceptsOnlyKnownDirectVavooHostsForTvVooFallback() throws Exception {
        String valid = catalogJson().replace(
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"",
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\","
                        + "\"pingUrl\":\"https://www.vypn.net/api/app/ping\","
                        + "\"catalogBase\":\"https://vavoo.to\""
        );
        ResolverCatalog.parse(valid);

        String invalid = valid.replace(
                "https://www.vypn.net/api/app/ping",
                "https://attacker.invalid/api/app/ping"
        );
        assertThrows(IOException.class, () -> ResolverCatalog.parse(invalid));
    }

    @Test
    public void m3uAliasesTakePriorityAndNameMapRemainsACompatibilityFallback()
            throws Exception {
        String json = catalogJson().replace(
                "\"config\":{\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"}",
                "\"config\":{\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"},"
                        + "\"compatibilityAliases\":{\"Sky Sports NFL\":[\"from-name\"]}"
        );
        ResolverDefinition definition = ResolverCatalog.parse(json).getById("tvvoo");
        Map<String, String> values = attributes("x-resolver-ids", "from-m3u;second");
        values.put("tvg-id", "SkySportsNFL.uk@TvVoo");
        Channel channel = new Channel(
                "Sky Sports NFL",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Sports",
                values
        );

        assertEquals("from-m3u", definition.resolverAliases(channel).get(0));
        assertEquals("from-name", definition.resolverAliases(channel).get(2));
    }

    private static String catalogJson() {
        return "{\"schemaVersion\":1,\"catalogVersion\":\"1\",\"providers\":["
                + "{\"id\":\"tvn\",\"name\":\"TVN\",\"engine\":\"tvn\","
                + "\"match\":{\"tvgIds\":[\"0104\"]},\"config\":{}},"
                + "{\"id\":\"tvvoo\",\"name\":\"TvVoo\",\"engine\":\"tvvoo\","
                + "\"match\":{\"tvgIdSuffixes\":[\"@TvVoo\"]},"
                + "\"config\":{\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"}},"
                + "{\"id\":\"highfly\",\"name\":\"Highfly\",\"engine\":\"highfly\","
                + "\"match\":{\"hosts\":[\"leaf.highfly.dev\"]},"
                + "\"config\":{\"directTemplate\":"
                + "\"https://leaf.highfly.dev/m3u/{id}/live.m3u8\"}}]}";
    }

    private static Channel channel(String tvgId, String stream, Map<String, String> extra) {
        Map<String, String> values = new LinkedHashMap<>(extra);
        values.put("tvg-id", tvgId);
        return new Channel("Test", URI.create(stream), null, "Test", values);
    }

    private static Map<String, String> attributes(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }
}
