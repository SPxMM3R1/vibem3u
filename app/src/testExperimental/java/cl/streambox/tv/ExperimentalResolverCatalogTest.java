package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ExperimentalResolverCatalogTest {
    @Test
    public void acceptsVavooOnlyInExperimentalBuild() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"catalogVersion\":\"1\","
                + "\"providers\":[{"
                + "\"id\":\"tvvoo\","
                + "\"name\":\"Vavoo directo experimental\","
                + "\"engine\":\"vavoo\","
                + "\"enabledByDefault\":false,"
                + "\"match\":{},"
                + "\"config\":{"
                + "\"pingUrl\":\"https://www.vavoo.tv/api/app/ping\","
                + "\"catalogBase\":\"https://vavoo.to\""
                + "}}]}";

        assertTrue(BuildConfig.ENABLE_EXPERIMENTAL_VAVOO);
        ResolverDefinition definition = ResolverCatalog.parse(json).getById("tvvoo");
        assertEquals("vavoo", definition.getEngine());
        assertFalse(definition.isEnabledByDefault());
    }
}
