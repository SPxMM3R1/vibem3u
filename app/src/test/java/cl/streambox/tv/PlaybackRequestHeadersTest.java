package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class PlaybackRequestHeadersTest {
    @Test
    public void removesUserAgentCaseInsensitiveAndKeepsProviderHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("user-agent", "VAVOO/2.6");
        headers.put("Referer", "https://sports.highfly.to/");
        headers.put("Origin", "https://sports.highfly.to");

        Map<String, String> sanitized = PlaybackRequestHeaders.withoutUserAgent(headers);

        assertFalse(sanitized.containsKey("user-agent"));
        assertEquals("https://sports.highfly.to/", sanitized.get("Referer"));
        assertEquals("https://sports.highfly.to", sanitized.get("Origin"));
        assertEquals(2, sanitized.size());
    }

    @Test
    public void doesNotMutateInputAndRemovesAllUserAgentVariants() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "VAVOO/2.6");
        headers.put("USER-AGENT", "duplicate");
        headers.put("Accept", "*/*");

        Map<String, String> sanitized = PlaybackRequestHeaders.withoutUserAgent(headers);

        assertNotSame(headers, sanitized);
        assertTrue(headers.containsKey("User-Agent"));
        assertTrue(headers.containsKey("USER-AGENT"));
        assertEquals(1, sanitized.size());
        assertEquals("*/*", sanitized.get("Accept"));
    }

    @Test
    public void nullOrOnlyUserAgentReturnsEmptyMap() {
        assertTrue(PlaybackRequestHeaders.withoutUserAgent(null).isEmpty());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "VAVOO/2.6");
        assertTrue(PlaybackRequestHeaders.withoutUserAgent(headers).isEmpty());
    }
}
