package cl.streambox.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Collections;
import org.junit.Test;

public final class PlaybackQualityPolicyTest {
    @Test
    public void arirangUsesAdaptiveQualityWhenNoPreferenceIsSaved() {
        assertTrue(PlaybackQualityPolicy.shouldUseAutomaticQuality(
                channel("Arirang TV", "ArirangTV.kr"),
                null,
                false
        ));
    }

    @Test
    public void explicitlySavedQualityOverridesArirangDefault() {
        assertFalse(PlaybackQualityPolicy.shouldUseAutomaticQuality(
                channel("Arirang TV", "ArirangTV.kr"),
                new PlaybackPreferences.QualityPreference(3_256_000, 1920, 1080),
                false
        ));
    }

    @Test
    public void explicitAutomaticSelectionRemainsAutomatic() {
        assertTrue(PlaybackQualityPolicy.shouldUseAutomaticQuality(
                channel("Canal de prueba", "example.test"),
                null,
                true
        ));
    }

    @Test
    public void otherChannelsKeepTheExistingDefault() {
        assertFalse(PlaybackQualityPolicy.shouldUseAutomaticQuality(
                channel("Canal de prueba", "example.test"),
                null,
                false
        ));
    }

    @Test
    public void channelNameAloneDoesNotSelectTheArirangPolicy() {
        assertFalse(PlaybackQualityPolicy.shouldUseAutomaticQuality(
                channel("Arirang TV", "different.stable.id"),
                null,
                false
        ));
    }

    private static Channel channel(String name, String tvgId) {
        return new Channel(
                name,
                URI.create("https://example.test/live.m3u8"),
                null,
                "Test",
                Collections.singletonMap("tvg-id", tvgId)
        );
    }
}
