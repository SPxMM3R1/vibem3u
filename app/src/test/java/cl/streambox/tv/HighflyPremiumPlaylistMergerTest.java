package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class HighflyPremiumPlaylistMergerTest {
    @Test
    public void keepsOrdinarySourcesAndAppendsTemporaryEvents() {
        Channel first = channel("First", "first", "direct");
        Channel ordinaryHighfly = channel(
                "Sky Sports F1",
                "now-sky-sports-f1-free",
                "highfly"
        );
        Channel second = channel("Second", "second", "direct");
        Channel event = channel(
                "Temporary Match",
                "event-match-123",
                "highfly"
        );

        Map<Integer, Playlist> playlists = new LinkedHashMap<>();
        playlists.put(1, new Playlist(
                Collections.singletonList(first),
                (URI) null
        ));
        playlists.put(2, new Playlist(
                Arrays.asList(ordinaryHighfly, second),
                (URI) null
        ));
        playlists.put(4, new Playlist(
                Collections.singletonList(event),
                (URI) null
        ));

        List<Channel> merged = HighflyPremiumPlaylistMerger.merge(playlists, 4);

        assertEquals(4, merged.size());
        assertEquals("First", merged.get(0).getName());
        assertEquals("Sky Sports F1", merged.get(1).getName());
        assertEquals("Second", merged.get(2).getName());
        assertEquals("Temporary Match", merged.get(3).getName());
    }

    private static Channel channel(String name, String id, String resolver) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tvg-id", id);
        if (!"direct".equals(resolver)) {
            attrs.put("x-resolver", resolver);
            attrs.put("x-resolver-id", id);
        }
        return new Channel(
                name,
                URI.create("https://example.org/" + id + ".m3u8"),
                null,
                "Source",
                attrs
        );
    }
}
