package cl.streambox.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HttpResourceCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistsBodyAndValidatorsAcrossCacheInstances() throws Exception {
        File directory = temporaryFolder.newFolder("resource-cache");
        String url = "https://example.test/epg.xml";
        byte[] body = "<tv/>".getBytes(StandardCharsets.UTF_8);

        HttpResourceCache first = new HttpResourceCache(directory, 3, 1024);
        first.commit(
                url,
                new HttpResourceCache.FetchResult(
                        new HttpResourceCache.CachedResource(body, "etag-1", "last-1"),
                        true,
                        true
                ),
                body
        );

        HttpResourceCache second = new HttpResourceCache(directory, 3, 1024);
        HttpResourceCache.CachedResource cached = second.readCached(url, 1024);

        assertNotNull(cached);
        assertArrayEquals(body, cached.getBytes());
        assertEquals("etag-1", cached.getEtag());
        assertEquals("last-1", cached.getLastModified());
        org.junit.Assert.assertTrue(cached.getCheckedAtMillis() > 0L);
    }

    @Test
    public void rewriteKeepsExistingValidators() throws Exception {
        File directory = temporaryFolder.newFolder("rewrite-cache");
        String url = "https://example.test/m3u.m3u";
        byte[] original = "#EXTM3U\nold".getBytes(StandardCharsets.UTF_8);
        byte[] rewritten = "#EXTM3U\nnew".getBytes(StandardCharsets.UTF_8);

        HttpResourceCache cache = new HttpResourceCache(directory, 3, 1024);
        cache.commit(
                url,
                new HttpResourceCache.FetchResult(
                        new HttpResourceCache.CachedResource(original, "etag-2", "last-2"),
                        true,
                        true
                ),
                original
        );
        cache.rewriteCached(url, rewritten);

        HttpResourceCache.CachedResource cached = cache.readCached(url, 1024);
        assertNotNull(cached);
        assertArrayEquals(rewritten, cached.getBytes());
        assertEquals("etag-2", cached.getEtag());
        assertEquals("last-2", cached.getLastModified());
    }

    @Test
    public void rewrittenBodyWithoutMetadataPersistsAcrossInstances() throws Exception {
        File directory = temporaryFolder.newFolder("render-cache");
        String key = "render-v1:https://example.test/logo.svg#156x108";
        byte[] rendered = new byte[]{1, 2, 3, 4};

        HttpResourceCache first = new HttpResourceCache(directory, 3, 1024);
        first.rewriteCached(key, rendered);

        HttpResourceCache second = new HttpResourceCache(directory, 3, 1024);
        HttpResourceCache.CachedResource cached = second.readCached(key, 1024);

        assertNotNull(cached);
        assertArrayEquals(rendered, cached.getBytes());
    }
}
