package cl.streambox.tv;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URI;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;

/** Opt-in live check. No provider URLs, signatures or tokens are logged or saved. */
@RunWith(AndroidJUnit4.class)
public final class VavooLiveInstrumentedTest {
    @Test public void resolvesSelectedTvVooIdentitiesWithoutPublicAddon() throws Exception {
        File input = new File("/data/local/tmp/vavoo-live-test.m3u");
        Assume.assumeTrue(input.isFile());
        File variants = new File("/data/local/tmp/vavoo-variants-test.json");
        if (variants.isFile()) PublishedTvVooVariants.update(new String(
                Files.readAllBytes(variants.toPath()), StandardCharsets.UTF_8), System.currentTimeMillis());
        String m3u = new String(Files.readAllBytes(input.toPath()), StandardCharsets.UTF_8);
        int successes = 0;
        for (Channel channel : M3uParser.parse(m3u, URI.create("https://example.com/1.m3u"))) {
            if (!"tvvoo".equals(channel.getAttributes().get("x-resolver"))) continue;
            long start = android.os.SystemClock.elapsedRealtime();
            TvVooStreamResolver resolver = new TvVooStreamResolver(ResolverDefinition.fallbackTvVoo());
            try {
                resolver.resolve(channel);
                successes++;
                android.util.Log.i("VavooLiveTest", channel.getName() + " OK ms="
                        + (android.os.SystemClock.elapsedRealtime() - start));
            } catch (Exception error) {
                StringBuilder detail = new StringBuilder();
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    detail.append(cause.getClass().getSimpleName()).append(":")
                            .append(SafePlaybackText.detail(cause.getMessage())).append("; ");
                }
                android.util.Log.i("VavooLiveTest", channel.getName() + " FAIL " + detail);
            } finally { resolver.clearSensitiveState(); }
        }
        assertTrue("No selected source resolved", successes > 0);
    }
}
