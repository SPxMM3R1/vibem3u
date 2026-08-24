package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public final class ExperimentalEngineIsolationTest {
    @Test
    public void stableBuildRejectsExperimentalVavooCatalog() {
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

        assertFalse(BuildConfig.ENABLE_EXPERIMENTAL_VAVOO);
        assertThrows(IOException.class, () -> ResolverCatalog.parse(json));
    }
}
