package cl.streambox.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;

public final class PlaybackPreferencesTest {
    @Test
    public void restoresChannelByTvgIdAfterPlaylistReordering() {
        Channel first = channel("Canal A", "a", "https://example.com/a.m3u8");
        Channel saved = channel("Canal B", "b", "https://example.com/b.m3u8");

        assertEquals(0, PlaybackPreferences.findChannelIndex(
                Arrays.asList(saved, first),
                PlaybackPreferences.channelIdentity(saved),
                1
        ));
    }

    @Test
    public void usesClampedIndexWhenSavedChannelDisappears() {
        Channel only = channel("Canal A", "a", "https://example.com/a.m3u8");

        assertEquals(0, PlaybackPreferences.findChannelIndex(
                Collections.singletonList(only),
                "tvg:missing",
                20
        ));
    }

    @Test
    public void lastChannelSnapshotKeepsTheResolverIdentityButNotBackups() {
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("tvg-id", "0104");
        attributes.put("x-resolver", "tvn");
        attributes.put("x-backup-stream", "http://example.com/backup.m3u8");
        Channel original = new Channel("TVN", URI.create("http://example.com/tvn.m3u8"),
                URI.create("https://example.com/tvn.png"), "Nacionales", attributes);

        Channel restored = PlaybackPreferences.parseSnapshot(PlaybackPreferences.snapshotJson(original));

        assertEquals(PlaybackPreferences.channelIdentity(original), PlaybackPreferences.channelIdentity(restored));
        assertEquals(original.getStreamUri(), restored.getStreamUri());
        assertEquals("tvn", restored.getAttributes().get("x-resolver"));
        assertEquals(null, restored.getAttributes().get("x-backup-stream"));
        assertEquals(null, PlaybackPreferences.parseSnapshot("{dañado"));
    }

    private static Channel channel(String name, String tvgId, String stream) {
        return new Channel(
                name,
                URI.create(stream),
                null,
                "TV",
                Collections.singletonMap("tvg-id", tvgId)
        );
    }
}
