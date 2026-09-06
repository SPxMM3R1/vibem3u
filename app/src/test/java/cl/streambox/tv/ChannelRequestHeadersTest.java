package cl.streambox.tv;

import org.junit.Test;
import java.net.URI;
import java.util.List;
import static org.junit.Assert.*;

public final class ChannelRequestHeadersTest {
    @Test public void optionsFollowOnlyTheirOwnChannelAndPreserveSignedUrl() {
        List<Channel> channels = M3uParser.parse("#EXTM3U\n"
                + "#EXTINF:-1,Protected\n#EXTVLCOPT:http-user-agent=TVClient/1\n"
                + "#EXTVLCOPT:http-referrer=https://provider.example/\n"
                + "#EXTVLCOPT:http-authorization=Bearer synthetic-test\n"
                + "https://cdn.example/live.m3u8?a=x%26y\n"
                + "#EXTINF:-1,Public\nhttps://cdn.example/public.m3u8\n", null);
        ResolvedPlaybackSource first = ResolvedPlaybackSource.direct(channels.get(0), "default");
        assertEquals("TVClient/1", first.getUserAgent());
        assertEquals("https://provider.example/", first.getRequestHeaders().get("Referer"));
        assertEquals("Bearer synthetic-test", first.getRequestHeaders().get("Authorization"));
        assertEquals("a=x%26y", first.getPlaybackUri().getRawQuery());
        assertTrue(ResolvedPlaybackSource.direct(channels.get(1), "default").getRequestHeaders().isEmpty());
    }

    @Test public void fallbackRetainsExplicitHeadersButDiskNeverStoresCredentials() {
        String input = "#EXTM3U\n#EXTINF:-1 http-cookie=\"session=synthetic\" "
                + "http-referrer=\"https://provider.example/\",Test\n"
                + "#EXTVLCOPT:http-authorization=Bearer synthetic-test\n"
                + "https://cdn.example/live.m3u8\n";
        Channel channel = M3uParser.parse(input, URI.create("https://example.org/list.m3u")).get(0);
        assertEquals("session=synthetic", ResolvedPlaybackSource.fallback(channel, "test", "ua")
                .getRequestHeaders().get("Cookie"));
        String disk = M3uCacheSanitizer.forDisk(input);
        assertFalse(disk.contains("synthetic"));
        assertTrue(disk.contains("http-referrer"));
    }

    @Test public void controlCharactersCannotBecomeRequestHeaders() {
        Channel channel = new Channel("Test", URI.create("https://example.org/live"), null, "",
                java.util.Collections.singletonMap("http-authorization", "Bearer ok\r\nInjected: x"));
        assertTrue(ChannelRequestHeaders.from(channel).isEmpty());
    }
}
