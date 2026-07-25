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
