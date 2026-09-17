package cl.streambox.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Decodes only the decimal-byte playlist representation used by papacito.cfd. */
final class HighflyHlsDecoder {
    static final int MAX_PLAYLIST_BYTES = 1024 * 1024;

    private HighflyHlsDecoder() {
    }

    static byte[] decodeIfNeeded(byte[] body) throws IOException {
        if (body == null || body.length == 0) return body;
        if (body.length > MAX_PLAYLIST_BYTES) {
            throw new IOException("Playlist Highfly demasiado grande.");
        }

        String text = new String(body, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        if (text.startsWith("#EXTM3U") || text.isEmpty()) return body;

        ByteArrayOutputStream decoded = new ByteArrayOutputStream(
                Math.min(text.length(), MAX_PLAYLIST_BYTES)
        );
        int value = 0;
        boolean inNumber = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current >= '0' && current <= '9') {
                inNumber = true;
                value = value * 10 + current - '0';
                if (value > 255) return body;
                continue;
            }
            if (!Character.isWhitespace(current)) return body;
            if (inNumber) {
                decoded.write(value);
                value = 0;
                inNumber = false;
            }
        }
        if (inNumber) decoded.write(value);

        byte[] result = decoded.toByteArray();
        String decodedText = new String(result, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        if (!decodedText.startsWith("#EXTM3U")) {
            throw new IOException("Playlist Highfly numérica inválida.");
        }
        return result;
    }
}
