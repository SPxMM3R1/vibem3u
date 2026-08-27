package cl.streambox.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Decodes the numeric playlist representation currently returned by MegaMedia. */
final class MeganoticiasHlsDecoder {
    static final int MAX_PLAYLIST_BYTES = 1024 * 1024;

    private MeganoticiasHlsDecoder() {}

    /**
     * Returns ordinary HLS unchanged and decodes a strict space-separated
     * decimal-byte response when its decoded value starts with {@code #EXTM3U}.
     * Error pages and malformed numeric responses are deliberately left
     * untouched so the normal HLS validation reports them as invalid.
     */
    static byte[] decodeIfNeeded(byte[] body) throws IOException {
        if (body == null || body.length == 0) return body;
        if (body.length > MAX_PLAYLIST_BYTES) {
            throw new IOException("Playlist Meganoticias demasiado grande.");
        }

        String text = new String(body, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        if (text.startsWith("#EXTM3U")) return body;
        if (text.isEmpty()) return body;

        ByteArrayOutputStream decoded = new ByteArrayOutputStream(
                Math.min(text.length(), MAX_PLAYLIST_BYTES)
        );
        int value = 0;
        boolean inNumber = false;
        int decodedCount = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current >= '0' && current <= '9') {
                inNumber = true;
                value = value * 10 + (current - '0');
                if (value > 255) return body;
                continue;
            }
            if (!Character.isWhitespace(current)) return body;
            if (!inNumber) continue;
            decoded.write(value);
            decodedCount++;
            value = 0;
            inNumber = false;
        }
        if (inNumber) {
            decoded.write(value);
            decodedCount++;
        }
        if (decodedCount == 0) return body;

        byte[] result = decoded.toByteArray();
        String decodedText = new String(result, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        return decodedText.startsWith("#EXTM3U") ? result : body;
    }
}
