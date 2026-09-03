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
    public void replacesExactHighflySlotsAndAppendsNewStableThenEvents() {
        Channel first = channel("First", "first", "direct");
        Channel oldF1 = channel("Old F1", "now-sky-sports-f1-free", "highfly");
        Channel second = channel("Second", "second", "direct");
        Channel premiumF1 = premiumChannel(
                "leaf:now-sky-sports-f1-free",
                "now-sky-sports-f1-free",
                "Premium F1",
                "estable"
        );
        Channel premiumNew = premiumChannel(
                "leaf:new-stable-channel",
                "new-stable-channel",
                "New Stable",
                "estable"
        );
        Channel event = premiumChannel(
                "streamed:match-123",
                "event-match-123",
                "Temporary Match",
                "evento"
        );

        Map<Integer, Playlist> playlists = new LinkedHashMap<>();
        playlists.put(1, new Playlist(
                Collections.singletonList(first),
                (URI) null
        ));
        playlists.put(2, new Playlist(
                Arrays.asList(oldF1, second),
                (URI) null
        ));
        playlists.put(3, new Playlist(
                Arrays.asList(premiumF1, premiumNew),
                (URI) null
        ));
        playlists.put(4, new Playlist(
                Collections.singletonList(event),
                (URI) null
        ));

        List<Channel> merged = HighflyPremiumPlaylistMerger.merge(playlists, 3, 4);

        assertEquals(5, merged.size());
        assertEquals("First", merged.get(0).getName());
        assertEquals("Premium F1", merged.get(1).getName());
        assertEquals("now-sky-sports-f1-free", merged.get(1)
                .getAttributes().get("x-resolver-id"));
        assertEquals("Second", merged.get(2).getName());
        assertEquals("New Stable", merged.get(3).getName());
        assertEquals("Temporary Match", merged.get(4).getName());
        assertEquals("now-sky-sports-f1-free", merged.get(1).getTvgId());
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

    private static Channel premiumChannel(
            String id,
            String resolverId,
            String name,
            String kind
    ) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tvg-id", "highfly-premium:" + id);
        attrs.put("x-resolver", "highfly");
        attrs.put("x-resolver-id", resolverId);
        if ("estable".equals(kind)) {
            attrs.put("x-highfly-premium-stable", "true");
            attrs.put("x-highfly-premium-list", "3");
        } else {
            attrs.put("x-highfly-premium", "true");
            attrs.put("x-highfly-premium-virtual", "true");
            attrs.put("x-highfly-premium-list", "4");
        }
        attrs.put("x-highfly-premium-id", id);
        attrs.put("x-highfly-premium-kind", kind);
        return new Channel(
                name,
                URI.create("https://leaf.highfly.dev/m3u/" + resolverId + "/live.m3u8"),
                null,
                "Lista " + ("evento".equals(kind) ? "4" : "3"),
                attrs
        );
    }
}
