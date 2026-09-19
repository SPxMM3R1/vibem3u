package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MediaFlowEndpointTest {
    @Test
    public void discoveryRouteUsesPlaceholderOriginAndVmuNamespace() throws Exception {
        ResolverDefinition definition = definition();
        Channel channel = new Channel(
                "BBC Two",
                URI.create("https://example.invalid/placeholder.m3u8"),
                null,
                "TvVoo",
                attributes("unitedkingdom")
        );
        URI endpoint = MediaFlowTvVooEndpoint.forChannel(
                definition,
                channel,
                URI.create("http://192.168.1.20:8888"),
                "vavoo_BBC%20TWO%7Cgroup%3Auk"
        );
        String value = endpoint.toString();
        assertTrue(value.contains("/cfg-uk-pxt_mfl-mfu_")
                && value.contains("-mfp_cGxhY2Vob2xkZXI/stream/tv/"));
        assertTrue(value.contains("vavoo_BBC%20TWO.vmu%7Cgroup%3Auk.json"));
        assertFalse(value.contains("192.168.1.20"));
        assertFalse(value.contains("password"));
        assertFalse(endpoint.getRawPath().contains("%2520"));
    }

    private static ResolverDefinition definition() {
        LinkedHashMap<String, String> config = new LinkedHashMap<>();
        config.put("endpointBase", "https://tvvoo.hayd.uk/stream/tv");
        return new ResolverDefinition(
                "tvvoo", "TvVoo", "tvvoo", true, 300_000L,
                Collections.emptySet(), Collections.emptyList(),
                Collections.emptySet(), config, Collections.emptyMap()
        );
    }

    private static java.util.Map<String, String> attributes(String country) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("tvg-country", country);
        result.put("x-resolver", "tvvoo");
        return result;
    }
}
