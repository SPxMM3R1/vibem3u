package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LargePublishedCatalogInstrumentedTest {
    @Test
    public void parsesPreparedCatalogOnAndroid() throws Exception {
        File input = new File("/data/local/tmp/vibem3u-catalog-test.json");
        Assume.assumeTrue(input.isFile());
        String json = new String(Files.readAllBytes(input.toPath()), StandardCharsets.UTF_8);
        long start = android.os.SystemClock.elapsedRealtime();
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(json);
        long elapsed = android.os.SystemClock.elapsedRealtime() - start;
        android.util.Log.i("VibeM3U-CatalogTest", "rows=" + catalog.getRows().size() + " ms=" + elapsed);
        assertEquals(16033, catalog.getRows().size());
        assertTrue(catalog.activeNumbers().size() > 10970);
        File playlistFile = new File("/data/local/tmp/vibem3u-playlist-test.m3u");
        Assume.assumeTrue(playlistFile.isFile());
        String playlist = new String(Files.readAllBytes(playlistFile.toPath()), StandardCharsets.UTF_8);
        java.util.List<Channel> channels = M3uParser.parse(playlist,
                new java.net.URI("https://example.com/2.m3u"));
        java.util.List<Channel> ordered = catalog.applyToPlayback(channels);
        android.util.Log.i("VibeM3U-CatalogTest", "playlist=" + channels.size()
                + " visible=" + ordered.size() + " totalMs="
                + (android.os.SystemClock.elapsedRealtime() - start));
        assertTrue(channels.size() > 10000);
        assertTrue(ordered.size() > 10000);
    }
}
