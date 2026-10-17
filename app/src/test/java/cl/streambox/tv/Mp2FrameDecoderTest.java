package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Compara el decodificador MP2 con la salida de ffmpeg para las tablas de asignación de
 * Layer II (A, B, C, D y MPEG-2 LSF), en estéreo y mono.
 */
public class Mp2FrameDecoderTest {

    @Test
    public void matchesFfmpegForEveryAllocationTable() throws IOException {
        assertMatchesReference("stereo48_128", 2, 48_000);   // tabla A, como los canales reales
        assertMatchesReference("mono48_96", 1, 48_000);      // tabla A, mono
        assertMatchesReference("stereo48_64", 2, 48_000);    // tabla C (32 kbps por canal)
        assertMatchesReference("stereo32_64", 2, 32_000);    // tabla D
        assertMatchesReference("stereo44_256", 2, 44_100);   // tabla B (30 subbandas)
        assertMatchesReference("mono24_64", 1, 24_000);      // MPEG-2 LSF
    }

    @Test
    public void parsesBroadcastHeaderAndRejectsOtherLayers() {
        // MPEG-1 Layer II, 128 kbps, 48 kHz, estéreo, sin CRC: lo que mandan los canales.
        byte[] header = {(byte) 0xFF, (byte) 0xFD, (byte) 0x84, (byte) 0x04};
        Mp2FrameDecoder.Header parsed = Mp2FrameDecoder.parseHeader(header, 0, header.length);
        assertNotNull(parsed);
        assertEquals(48_000, parsed.sampleRate);
        assertEquals(2, parsed.channels);
        assertEquals(384, parsed.frameBytes);
        assertFalse(parsed.crc);

        byte[] layer3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x94, (byte) 0x04};
        assertNull(Mp2FrameDecoder.parseHeader(layer3, 0, layer3.length));
        byte[] freeFormat = {(byte) 0xFF, (byte) 0xFD, (byte) 0x04, (byte) 0x04};
        assertNull(Mp2FrameDecoder.parseHeader(freeFormat, 0, freeFormat.length));
    }

    @Test
    public void truncatedFrameBecomesSilence() throws IOException {
        byte[] mp2 = resource("mp2/stereo48_128.mp2");
        Mp2FrameDecoder.Header header = Mp2FrameDecoder.parseHeader(mp2, 0, mp2.length);
        assertNotNull(header);
        byte[] truncated = java.util.Arrays.copyOf(mp2, header.frameBytes / 2);
        short[] pcm = new short[Mp2FrameDecoder.SAMPLES_PER_FRAME * 2];
        java.util.Arrays.fill(pcm, (short) 123);
        assertFalse(new Mp2FrameDecoder().decodeFrame(truncated, 0, header, pcm, 0));
        for (short sample : pcm) assertEquals(0, sample);
    }

    private static void assertMatchesReference(String name, int channels, int sampleRate)
            throws IOException {
        byte[] mp2 = resource("mp2/" + name + ".mp2");
        byte[] reference = resource("mp2/" + name + ".pcm");
        Mp2FrameDecoder decoder = new Mp2FrameDecoder();
        short[] pcm = new short[reference.length / 2];
        int written = 0;
        int offset = 0;
        while (offset < mp2.length) {
            Mp2FrameDecoder.Header header = Mp2FrameDecoder.parseHeader(mp2, offset, mp2.length);
            assertNotNull(name + ": cabecera en " + offset, header);
            assertEquals(name, channels, header.channels);
            assertEquals(name, sampleRate, header.sampleRate);
            assertTrue(name, decoder.decodeFrame(mp2, offset, header, pcm, written));
            written += Mp2FrameDecoder.SAMPLES_PER_FRAME * channels;
            offset += header.frameBytes;
        }
        assertEquals(name, reference.length / 2, written);

        double signal = 0;
        double error = 0;
        int maxDifference = 0;
        for (int i = 0; i < written; i++) {
            int expected = (short) ((reference[2 * i] & 0xFF) | (reference[2 * i + 1] << 8));
            int difference = pcm[i] - expected;
            signal += (double) expected * expected;
            error += (double) difference * difference;
            maxDifference = Math.max(maxDifference, Math.abs(difference));
        }
        // Redondeo distinto al de ffmpeg: a lo más 2 de 32768, muy por debajo de lo audible.
        assertTrue(name + ": diferencia máxima " + maxDifference, maxDifference <= 2);
        assertTrue(name + ": SNR", 10 * Math.log10(signal / Math.max(error, 1e-9)) > 75);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = Mp2FrameDecoderTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("Falta " + name, input);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
