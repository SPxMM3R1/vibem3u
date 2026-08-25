package cl.streambox.tv;

import org.junit.Test;

import java.io.ByteArrayInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class TokenHttpClientTest {
    @Test
    public void prefixReadAcceptsAnOriginThatIgnoresRange() throws Exception {
        byte[] completeSegment = new byte[3 * 1024 * 1024];
        completeSegment[0] = 0x47;
        completeSegment[4095] = 0x47;

        byte[] prefix = TokenHttpClient.readPrefix(
                new ByteArrayInputStream(completeSegment),
                4096
        );

        assertEquals(4096, prefix.length);
        assertArrayEquals(
                new byte[]{0x47, 0x00, 0x00, 0x00},
                new byte[]{prefix[0], prefix[1], prefix[2], prefix[3]}
        );
    }
}
