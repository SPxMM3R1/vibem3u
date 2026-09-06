package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void scopesTvVooHttpFallbackToKnownEphemeralCdnPath() {
        assertTrue(TvVooStreamResolver.isScopedTvVooHttpFallback(URI.create(
                "https://node.ngolpdkyoctjcddxshli469r.org/sunshine/live/index.m3u8"
        )));
        assertFalse(TvVooStreamResolver.isScopedTvVooHttpFallback(URI.create(
                "https://node.ngolpdkyoctjcddxshli469r.org/other/live.m3u8"
        )));
        assertFalse(TvVooStreamResolver.isScopedTvVooHttpFallback(URI.create(
                "https://node.example.org/sunshine/live/index.m3u8"
        )));
        assertFalse(TvVooStreamResolver.isScopedTvVooHttpFallback(URI.create(
                "https://ngolpdkyoctjcddxshli469r.org/sunshine/live/index.m3u8"
        )));
    }

    @Test
    public void usesValidatedHttpCandidateBeforeExpiredCdnHttps() throws Exception {
        List<URI> attempts = new ArrayList<>();
        TvVooStreamResolver.HlsCandidateValidator validator = (
                playbackUri,
                headers,
                strictValidation,
                listener
        ) -> attempts.add(playbackUri);
        URI https = URI.create(
                "https://node.ngolpdkyoctjcddxshli469r.org/sunshine/live/index.m3u8"
        );

        URI accepted = TvVooStreamResolver.validateCandidate(
                validator,
                https,
                true,
                Collections.emptyMap(),
                true,
                ResolutionProgressListener.NONE
        );

        assertEquals("http", accepted.getScheme());
        assertEquals(1, attempts.size());
        assertEquals("http", attempts.get(0).getScheme());
    }

    @Test
    public void schemeFallbackPreservesEscapedPathQueryFragmentAndPort() throws Exception {
        List<URI> attempts = new ArrayList<>();
        TvVooStreamResolver.HlsCandidateValidator validator = (
                playbackUri,
                headers,
                strictValidation,
                listener
        ) -> attempts.add(playbackUri);
        URI published = URI.create(
                "https://node.ngolpdkyoctjcddxshli469r.org:8443/"
                        + "sunshine/live%2Findex.m3u8?token=a%2Bb&x=%2F#frag%20ment"
        );

        URI accepted = TvVooStreamResolver.validateCandidate(
                validator,
                published,
                true,
                Collections.emptyMap(),
                true,
                ResolutionProgressListener.NONE
        );

        assertEquals(
                "http://node.ngolpdkyoctjcddxshli469r.org:8443/"
                        + "sunshine/live%2Findex.m3u8?token=a%2Bb&x=%2F#frag%20ment",
                accepted.toString()
        );
        assertEquals(accepted.toString(), attempts.get(0).toString());
    }
}
