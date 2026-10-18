package cl.streambox.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Filtro de idioma sobre la PAT/PMT reales de un canal con español (PID 0xC13) e inglés (0xC14):
 * solo cambia el tipo de las pistas descartadas y el CRC, y nunca toca nada si no corresponde.
 */
public class TsAudioPmtFilterTest {
    private static final int PMT_OFFSET = 188;

    @Test
    public void discoversBothLanguagesWithoutChangingBytes() throws IOException {
        byte[] original = resource();
        byte[] data = original.clone();
        TsAudioSession session = run(data, "");

        assertEquals(Arrays.asList("es", "en"), session.languages());
        assertEquals("", session.getAppliedLanguage());
        assertArrayEquals(original, data);
    }

    @Test
    public void choosingEnglishDisablesSpanishAndKeepsCrcValid() throws IOException {
        byte[] original = resource();
        byte[] data = original.clone();
        TsAudioSession session = run(data, "en");

        assertEquals("en", session.getAppliedLanguage());
        int sectionStart = PMT_OFFSET + 5; // cabecera de 4 bytes + pointer_field 0
        int sectionEnd = sectionStart + 3 + (((data[sectionStart + 1] & 0x0F) << 8)
                | (data[sectionStart + 2] & 0xFF));
        assertEquals(0, TsAudioPmtFilter.crc32(data, sectionStart, sectionEnd));
        assertEquals(TsAudioPmtFilter.DISABLED_STREAM_TYPE, streamTypeOf(data, 0xC13));
        assertEquals(0x0F, streamTypeOf(data, 0xC14));
        // Solo cambian el tipo de la pista española y los 4 bytes del CRC.
        int changed = 0;
        for (int index = 0; index < data.length; index++) if (data[index] != original[index]) changed++;
        assertEquals(true, changed >= 2 && changed <= 5);
        // La PMT editada se vuelve a leer igual: ahora con un solo audio.
        TsAudioSession reread = run(data.clone(), "");
        assertEquals(Arrays.asList("en"), reread.languages());
    }

    @Test
    public void choosingSpanishDisablesEnglish() throws IOException {
        byte[] data = resource().clone();
        run(data, "spa");
        assertEquals(0x0F, streamTypeOf(data, 0xC13));
        assertEquals(TsAudioPmtFilter.DISABLED_STREAM_TYPE, streamTypeOf(data, 0xC14));
    }

    @Test
    public void unknownLanguageOrBrokenCrcLeavesEverythingIntact() throws IOException {
        byte[] original = resource();
        byte[] data = original.clone();
        run(data, "fr");
        assertArrayEquals(original, data);

        byte[] broken = original.clone();
        broken[PMT_OFFSET + 30] ^= 0x01; // otro byte de la PMT: el CRC ya no calza
        byte[] expected = broken.clone();
        TsAudioSession session = run(broken, "en");
        assertArrayEquals(expected, broken);
        assertEquals("", session.getAppliedLanguage());
    }

    @Test
    public void rewriterGivesTheSameResultForAnyReadSizeAndPassesNonTsUntouched()
            throws IOException {
        byte[] packets = resource();
        byte[] stream = new byte[packets.length * 20];
        for (int copy = 0; copy < 20; copy++) {
            System.arraycopy(packets, 0, stream, copy * packets.length, packets.length);
        }
        byte[] expected = stream.clone();
        TsAudioSession reference = new TsAudioSession("en");
        TsAudioPmtFilter filter = reference.newFilter();
        for (int offset = 0; offset < expected.length; offset += 188) {
            filter.processPacket(expected, offset);
        }
        for (int seed = 1; seed <= 5; seed++) {
            assertArrayEquals(expected, rewrite(stream, "en", 0, new Random(seed)));
        }

        // Lectura reanudada a mitad de paquete: los bytes previos al siguiente paquete pasan.
        // La sesión es la misma del canal, que ya vio la PAT en la lectura anterior.
        TsAudioSession shared = new TsAudioSession("en");
        shared.newFilter().processPacket(stream.clone(), 0);
        byte[] tail = Arrays.copyOfRange(stream, 100, stream.length);
        byte[] rewritten = rewrite(tail, shared, 100, new Random(9));
        assertArrayEquals(Arrays.copyOfRange(expected, 100, expected.length), rewritten);

        byte[] playlist = "#EXTM3U\n#EXTINF:2.0,\nseg1.ts\n".getBytes();
        assertArrayEquals(playlist, rewrite(playlist, "en", 0, new Random(3)));
    }

    private static TsAudioSession run(byte[] data, String language) {
        TsAudioSession session = new TsAudioSession(language);
        TsAudioPmtFilter filter = session.newFilter();
        for (int offset = 0; offset + 188 <= data.length; offset += 188) {
            filter.processPacket(data, offset);
        }
        return session;
    }

    private static byte[] rewrite(byte[] input, String language, long startPosition, Random random) {
        return rewrite(input, new TsAudioSession(language), startPosition, random);
    }

    private static byte[] rewrite(byte[] input, TsAudioSession session, long startPosition,
            Random random) {
        TsAudioStreamRewriter rewriter = new TsAudioStreamRewriter(session.newFilter(), startPosition);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] target = new byte[4096];
        int position = 0;
        boolean ended = false;
        while (true) {
            while (!rewriter.hasReady() && !ended) {
                if (position >= input.length) {
                    ended = true;
                    rewriter.commit(0, true);
                    break;
                }
                int writeAt = rewriter.prepareWrite();
                int count = Math.min(Math.min(rewriter.writableSpace(), 1 + random.nextInt(700)),
                        input.length - position);
                System.arraycopy(input, position, rewriter.buffer(), writeAt, count);
                position += count;
                rewriter.commit(count, false);
            }
            if (!rewriter.hasReady()) break;
            int count = rewriter.drain(target, 0, 1 + random.nextInt(target.length));
            output.write(target, 0, count);
        }
        return output.toByteArray();
    }

    /** Tipo de la pista {@code pid} en la PMT del recurso (segundo paquete). */
    private static int streamTypeOf(byte[] data, int pid) {
        int sectionStart = PMT_OFFSET + 5;
        int sectionEnd = sectionStart + 3 + (((data[sectionStart + 1] & 0x0F) << 8)
                | (data[sectionStart + 2] & 0xFF));
        int programInfoLength = ((data[sectionStart + 10] & 0x0F) << 8)
                | (data[sectionStart + 11] & 0xFF);
        for (int position = sectionStart + 12 + programInfoLength; position + 5 <= sectionEnd - 4; ) {
            int entryPid = ((data[position + 1] & 0x1F) << 8) | (data[position + 2] & 0xFF);
            int infoLength = ((data[position + 3] & 0x0F) << 8) | (data[position + 4] & 0xFF);
            if (entryPid == pid) return data[position] & 0xFF;
            position += 5 + infoLength;
        }
        return -1;
    }

    private static byte[] resource() throws IOException {
        try (InputStream input = TsAudioPmtFilterTest.class.getClassLoader()
                .getResourceAsStream("ts/pat_pmt_es_en.bin")) {
            assertNotNull("Falta ts/pat_pmt_es_en.bin", input);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
