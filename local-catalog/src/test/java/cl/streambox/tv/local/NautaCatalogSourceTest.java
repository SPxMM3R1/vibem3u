package cl.streambox.tv.local;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NautaCatalogSourceTest {
    private MockWebServer server;
    private NautaCatalogSource source;

    @Before public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        source = new NautaCatalogSource(new OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(), server.url("/").toString());
    }

    @After public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test public void catalogExportsOnlyStableNamesAndDerivedIdentity() throws Exception {
        server.enqueue(new MockResponse().setBody(manifest()));
        server.enqueue(new MockResponse().setBody("{\"metas\":["
                + "{\"id\":\"opaque-private-resource-1\",\"type\":\"tv\",\"name\":\" ESPN 1 | Chile\",\"url\":\"https://temporary.example/live.m3u8?token=secret\"},"
                + "{\"id\":\"opaque-private-resource-2\",\"type\":\"tv\",\"name\":\" ESPN 1 | Chile\"},"
                + "{\"id\":\"opaque-private-resource-3\",\"type\":\"tv\",\"name\":\"bad://locator\"},"
                + "{\"id\":\"opaque-private-resource-4\",\"type\":\"movie\",\"name\":\"Not a channel\"}]}"));

        JSONObject categories = source.categories();
        JSONObject catalog = source.catalog("cat_4");

        JSONArray foundCategories = categories.getJSONArray("categories");
        assertEquals(2, foundCategories.length());
        assertEquals("cat_4", foundCategories.getJSONObject(1).getString("id"));
        JSONArray channels = catalog.getJSONArray("channels");
        assertEquals(1, channels.length());
        JSONObject channel = channels.getJSONObject(0);
        assertEquals(" ESPN 1 | Chile", channel.getString("name"));
        assertEquals("cat_4", channel.getString("categoryId"));
        assertEquals(expectedId(" ESPN 1 | Chile"), channel.getString("tvgId"));

        String sanitized = categories.toString() + catalog;
        assertFalse(sanitized.contains("opaque-private-resource"));
        assertFalse(sanitized.contains("temporary.example"));
        assertFalse(sanitized.contains(".m3u8"));
        assertFalse(sanitized.contains("token="));
        RecordedRequest manifest = server.takeRequest();
        RecordedRequest request = server.takeRequest();
        assertEquals("/manifest.json", manifest.getPath());
        assertEquals("/catalog/tv/cat_4.json", request.getPath());
    }

    @Test public void unknownCategoryIsRejectedBeforeCatalogFetch() throws Exception {
        server.enqueue(new MockResponse().setBody(manifest()));
        source.categories();

        assertThrows(IllegalArgumentException.class, () -> source.catalog("cat_999"));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void redirectIsNotFollowedAndErrorDoesNotExposeItsDestination() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "https://elsewhere.example/private.json"));

        IOException failure = assertThrows(IOException.class, source::categories);

        assertTrue(failure.getMessage().contains("HTTP 302"));
        assertFalse(failure.getMessage().contains("elsewhere.example"));
        assertEquals(1, server.getRequestCount());
        assertNotNull(server.takeRequest());
    }

    private static String manifest() {
        return "{\"catalogs\":["
                + "{\"id\":\"nautatv_catalog\",\"name\":\"Todos los canales\",\"type\":\"tv\"},"
                + "{\"id\":\"cat_4\",\"name\":\"Deportes\",\"type\":\"tv\"}]}";
    }

    private static String expectedId(String exactName) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(exactName.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return "Nauta." + hex.substring(0, 24) + "@Nauta";
    }
}
