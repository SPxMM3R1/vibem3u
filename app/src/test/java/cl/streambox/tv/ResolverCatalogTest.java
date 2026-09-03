package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
    public void currentMeganoticiasIdMatchesDynamicResolver() throws Exception {
        String json = catalogJson().replace(
                "\"providers\":[",
                "\"providers\":["
                        + "{\"id\":\"meganoticias\",\"name\":\"Meganoticias\","
                        + "\"engine\":\"meganoticias\","
                        + "\"match\":{\"tvgIds\":[\"Meganoticias.cl\","
                        + "\"MeganoticiasAhora.cl\"]},\"config\":{}} ,"
        );

        ResolverCatalog parsed = ResolverCatalog.parse(json);

        assertEquals("meganoticias", parsed.find(channel(
                "Meganoticias.cl",
                "https://mdstrm.com/live-stream-playlist/current.m3u8",
                attributes()
        )).getId());
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
    public void retiredTwentyFourHoursProviderIsIgnoredFromOlderCatalogues() throws Exception {
        String legacy = catalogJson().replace(
                "\"providers\":[",
                "\"providers\":["
                        + "{\"id\":\"24horas\",\"name\":\"24 Horas\","
                        + "\"engine\":\"24horas\","
                        + "\"match\":{\"tvgIds\":[\"0201\"]},\"config\":{}},"
        );

        ResolverCatalog catalog = ResolverCatalog.parse(legacy);

        assertNull(catalog.getById("24horas"));
        assertNull(catalog.find(channel(
                "0201",
                "https://mdstrm.com/live-stream-playlist/current.m3u8",
                attributes("x-resolver", "24horas")
        )));
        assertNull(catalog.find(channel(
                "0201",
                "https://mdstrm.com/live-stream-playlist/current.m3u8",
                attributes()
        )));
        assertEquals(3, catalog.getProviders().size());
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
    public void m3uAliasesAreAuthoritativeOverTheCompatibilityMap()
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
        assertEquals("second", definition.resolverAliases(channel).get(1));
        assertEquals(2, definition.resolverAliases(channel).size());
    }

    @Test
    public void recipeMustBeAuthorisedByBothM3uAndTrustedCatalog() throws Exception {
        String json = catalogJson().replace(
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"",
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\","
                        + "\"recipeId\":\"bounded-payload-v1\","
                        + "\"validationMode\":\"media-signature-v1\""
        );
        ResolverDefinition definition = ResolverCatalog.parse(json).getById("tvvoo");
        Channel authorised = channel(
                "Test@TvVoo",
                "https://example.org/fallback.m3u8",
                attributes("x-resolver-recipe", "bounded-payload-v1")
        );
        Channel unknown = channel(
                "Test@TvVoo",
                "https://example.org/fallback.m3u8",
                attributes("x-resolver-recipe", "download-code-v1")
        );

        assertTrue(definition.usesRecipe(authorised, "bounded-payload-v1"));
        assertFalse(definition.usesRecipe(unknown, "bounded-payload-v1"));
    }

    @Test
    public void rejectsUnknownDeclarativeRecipe() {
        String invalid = catalogJson().replace(
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"",
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\","
                        + "\"recipeId\":\"download-code-v1\""
        );
        assertThrows(IOException.class, () -> ResolverCatalog.parse(invalid));
    }

    @Test
    public void rejectsRecipeWithoutAnExplicitValidationMode() {
        String invalid = catalogJson().replace(
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"",
                "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\","
                        + "\"recipeId\":\"bounded-payload-v1\""
        );
        assertThrows(IOException.class, () -> ResolverCatalog.parse(invalid));
    }

    @Test
    public void acceptsMoreThanTheLegacy128AliasChannelLimit() throws Exception {
        ResolverDefinition definition = ResolverCatalog.parse(
                catalogWithAliases(246, 1)
        ).getById("tvvoo");
        Map<String, String> attributes = attributes();
        attributes.put("tvg-id", "Channel 245@TvVoo");
        Channel channel = new Channel(
                "Channel 245",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Sports",
                attributes
        );

        assertEquals("alias-245-0", definition.resolverAliases(channel).get(0));
    }

    @Test
    public void rejectsAliasCatalogThatExceedsTheAggregateValueBudget() {
        assertThrows(IOException.class, () -> ResolverCatalog.parse(
                catalogWithAliases(500, 9)
        ));
    }

    @Test
    public void deduplicatesCompatibilityAliasesWithoutChangingTheirOrder()
            throws Exception {
        String json = catalogWithAliases(1, 1).replace(
                "[\"alias-0-0\"]",
                "[\"first\",\"first\",\"second\"]"
        );
        ResolverDefinition definition = ResolverCatalog.parse(json).getById("tvvoo");
        Map<String, String> attributes = attributes();
        attributes.put("tvg-id", "Channel 0@TvVoo");
        Channel channel = new Channel(
                "Channel 0",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Sports",
                attributes
        );

        assertEquals(2, definition.resolverAliases(channel).size());
        assertEquals("first", definition.resolverAliases(channel).get(0));
        assertEquals("second", definition.resolverAliases(channel).get(1));
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

    private static String catalogWithAliases(int channelCount, int aliasesPerChannel) {
        StringBuilder aliases = new StringBuilder("\"compatibilityAliases\":{");
        for (int channel = 0; channel < channelCount; channel++) {
            if (channel > 0) aliases.append(',');
            aliases.append("\"Channel ").append(channel).append("\":[");
            for (int alias = 0; alias < aliasesPerChannel; alias++) {
                if (alias > 0) aliases.append(',');
                aliases.append("\"alias-").append(channel).append('-')
                        .append(alias).append("\"");
            }
            aliases.append(']');
        }
        aliases.append('}');
        return "{\"schemaVersion\":1,\"catalogVersion\":\"test\",\"providers\":[{"
                + "\"id\":\"tvvoo\",\"name\":\"TvVoo\",\"engine\":\"tvvoo\","
                + "\"match\":{\"tvgIdSuffixes\":[\"@TvVoo\"]},"
                + "\"config\":{\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv\"},"
                + aliases + "}]}";
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
