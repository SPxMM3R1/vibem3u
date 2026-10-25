package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class AdultChannelsTest {
    /** PBKDF2-SHA256 de «1234», 200 000 vueltas, calculado aparte con Python (hashlib). */
    private static final String SALT = "00112233445566778899aabbccddeeff";
    private static final String HASH = "98cd56f19733a361768b76706415776c6f3e161aef907b7e88c5ea3fe5639ab7";

    @Test
    public void acceptsOnlyTheKeyBehindTheFingerprint() {
        assertTrue(AdultChannels.matches("1234", SALT, HASH, AdultChannels.ITERATIONS));
        assertFalse(AdultChannels.matches("1235", SALT, HASH, AdultChannels.ITERATIONS));
        assertFalse(AdultChannels.matches("12a4", SALT, HASH, AdultChannels.ITERATIONS));
        assertFalse(AdultChannels.matches("123", SALT, HASH, AdultChannels.ITERATIONS));
        assertFalse(AdultChannels.matches(null, SALT, HASH, AdultChannels.ITERATIONS));
    }

    @Test
    public void publishedListDecidesWhichChannelsAreAdult() {
        assertTrue(AdultChannels.update("{\"schema\":1,\"channels\":[\"PublicDirect.p1\",\"PublicDirect.p2\"]}"));
        assertTrue(AdultChannels.isAdult(channel("PublicDirect.p1", "Playboy", "Adultos")));
        assertFalse(AdultChannels.isAdult(channel("PublicDirect.sw", "Adult Swim", "Adultos")));
        assertFalse(AdultChannels.update("{\"schema\":1,\"channels\":[\"PublicDirect.p2\",\"PublicDirect.p1\"]}"));
        assertFalse("un documento inválido no borra la lista vigente", AdultChannels.update("{\"schema\":2}"));
        assertTrue(AdultChannels.isAdult(channel("PublicDirect.p2", "Brazzers TV", "Adultos")));
    }

    @Test
    public void parseIgnoresWrongSchemaAndBlankIds() {
        assertTrue(AdultChannels.parse("{\"schema\":2,\"channels\":[\"a\"]}").isEmpty());
        assertEquals(1, AdultChannels.parse("{\"schema\":1,\"channels\":[\"a\",\" \",\"\"]}").size());
        assertTrue(AdultChannels.parse("no es json").isEmpty());
    }

    private static Channel channel(String tvgId, String name, String group) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("tvg-id", tvgId);
        return new Channel(name, URI.create("https://example.test/" + name.replace(' ', '_') + ".m3u8"),
                null, group, attributes);
    }
}
