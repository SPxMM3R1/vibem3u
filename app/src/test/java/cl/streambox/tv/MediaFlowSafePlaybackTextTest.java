package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MediaFlowSafePlaybackTextTest {
    @Test
    public void redactsMediaFlowPasswordAndVavooDestination() {
        String value = SafePlaybackText.detail(
                "GET https://proxy.invalid/extractor/video"
                        + "?api_password=super-secret"
                        + "&password=also-secret"
                        + "&h_Authorization=Bearer-secret"
                        + "&d=https%3A%2F%2Fvavoo.to%2Fvavoo-iptv%2Fprivate"
        );
        assertTrue(value.contains("api_password=[oculto]"));
        assertTrue(value.contains("password=[oculto]"));
        assertTrue(value.contains("h_Authorization=[oculto]"));
        assertTrue(value.contains("d=[oculto]"));
        assertFalse(value.contains("super-secret"));
        assertFalse(value.contains("also-secret"));
        assertFalse(value.contains("Bearer-secret"));
        assertFalse(value.contains("vavoo-iptv%2Fprivate"));
    }
}
