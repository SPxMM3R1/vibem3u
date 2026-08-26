package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SafePlaybackTextTest {
    @Test
    public void redactsCredentialsFromTechnicalUrl() {
        String value = SafePlaybackText.detail(
                "GET https://api.mega.cl/api/v1/mdstrm"
                        + "?id=stream-123&ua=Mozilla%2F5.0&type=live"
                        + "&process=access_token&key=server-secret"
        );

        assertTrue(value.contains("https://api.mega.cl/api/v1/mdstrm"));
        assertTrue(value.contains("id=stream-123"));
        assertTrue(value.contains("ua=[oculto]"));
        assertTrue(value.contains("key=[oculto]"));
        assertFalse(value.contains("Mozilla%2F5.0"));
        assertFalse(value.contains("server-secret"));
    }

    @Test
    public void redactsTokenLikePathAndProgressDetail() {
        ResolutionProgress progress = ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "GET https://cdn.example/sunshine/path-secret/index.m3u8"
                        + "?access_token=playlist-secret"
        );

        assertTrue(progress.getDetail().contains("https://cdn.example/sunshine/[oculto]/index.m3u8"));
        assertTrue(progress.getDetail().contains("access_token=[oculto]"));
        assertFalse(progress.getDetail().contains("path-secret"));
        assertFalse(progress.getDetail().contains("playlist-secret"));
    }
}
