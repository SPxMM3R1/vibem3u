package cl.streambox.tv;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

public final class MeganoticiasHlsDecoderTest {
    @Test
    public void leavesOrdinaryHlsUntouched() throws Exception {
        byte[] plain = "#EXTM3U\n#EXT-X-VERSION:3\n".getBytes(StandardCharsets.UTF_8);

        assertSame(plain, MeganoticiasHlsDecoder.decodeIfNeeded(plain));
    }

    @Test
    public void decodesDecimalAsciiPlaylist() throws Exception {
        byte[] encoded = (
                "35 69 88 84 77 51 85 10 "
                        + "35 69 88 45 86 69 82 83 73 79 78 58 51 10"
        ).getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(
                "#EXTM3U\n#EXT-X-VERSION:3\n".getBytes(StandardCharsets.UTF_8),
                MeganoticiasHlsDecoder.decodeIfNeeded(encoded)
        );
    }

    @Test
    public void leavesMalformedNumericResponseForNormalValidation() throws Exception {
        byte[] malformed = "35 69 88 84 77 999".getBytes(StandardCharsets.UTF_8);

        assertSame(malformed, MeganoticiasHlsDecoder.decodeIfNeeded(malformed));
    }
}
