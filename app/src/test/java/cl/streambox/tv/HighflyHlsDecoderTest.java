package cl.streambox.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class HighflyHlsDecoderTest {
    @Test
    public void leavesOrdinaryPlaylistUntouched() throws Exception {
        byte[] body = "#EXTM3U\n#EXT-X-VERSION:3\n".getBytes(StandardCharsets.UTF_8);
        assertSame(body, HighflyHlsDecoder.decodeIfNeeded(body));
    }

    @Test
    public void decodesDecimalBytesSeparatedByLines() throws Exception {
        byte[] encoded = "35\n69\n88\n84\n45\n88\n45\n86\n69\n82\n83\n73\n79\n78\n58\n51\n10\n"
                .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(
                "#EXTM3U\n#EXT-X-VERSION:3\n".getBytes(StandardCharsets.UTF_8),
                HighflyHlsDecoder.decodeIfNeeded(encoded)
        );
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsMalformedNumericPlaylist() throws Exception {
        HighflyHlsDecoder.decodeIfNeeded("35 69 999".getBytes(StandardCharsets.UTF_8));
    }
}
