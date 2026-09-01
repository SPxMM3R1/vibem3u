package cl.streambox.tv;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HlsStreamValidatorTest {
    @Test
    public void probesPenultimateThenNewestSegment() {
        assertArrayEquals(new int[]{4, 5, 3}, HlsStreamValidator.recentProbeOrder(6));
    }

    @Test
    public void handlesShortLiveWindowsWithoutDuplicates() {
        assertArrayEquals(new int[]{0}, HlsStreamValidator.recentProbeOrder(1));
        assertArrayEquals(new int[]{0, 1}, HlsStreamValidator.recentProbeOrder(2));
    }

    @Test
    public void acceptsTransportStreamAndFragmentedMp4Signatures() {
        byte[] transport = new byte[512];
        transport[0] = 0x47;
        transport[188] = 0x47;
        byte[] fragmentedMp4 = new byte[]{0, 0, 0, 24, 'm', 'o', 'o', 'f', 0, 0};

        assertTrue(HlsStreamValidator.isRecognizedMediaSample(
                transport, "video/mp2t"
        ));
        assertTrue(HlsStreamValidator.isRecognizedMediaSample(
                fragmentedMp4, "video/mp4"
        ));
    }

    @Test
    public void rejectsHtmlJsonAndArbitraryNonMediaBytes() {
        assertFalse(HlsStreamValidator.isRecognizedMediaSample(
                "<html>temporary error</html>".getBytes(StandardCharsets.UTF_8),
                "text/html"
        ));
        assertFalse(HlsStreamValidator.isRecognizedMediaSample(
                "{\"url\":\"not a segment\"}".getBytes(StandardCharsets.UTF_8),
                "application/json"
        ));
        assertFalse(HlsStreamValidator.isRecognizedMediaSample(
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                "application/octet-stream"
        ));
    }
}
