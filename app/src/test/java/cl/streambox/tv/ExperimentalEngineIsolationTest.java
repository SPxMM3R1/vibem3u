package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class ExperimentalEngineIsolationTest {
    @Test
    public void onlyExperimentalBuildAcceptsExperimentalVavooCatalog() throws Exception {
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

        boolean experimental = "experimental".equals(BuildConfig.BUILD_TYPE);
        assertEquals(experimental, BuildConfig.ENABLE_EXPERIMENTAL_VAVOO);
        if (experimental) {
            assertEquals("vavoo", ResolverCatalog.parse(json).getProviders().get(0).getEngine());
        } else {
            assertThrows(IOException.class, () -> ResolverCatalog.parse(json));
        }
    }
}
