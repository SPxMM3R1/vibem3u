package cl.streambox.tv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public final class EpgRepository {
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_EPG_BYTES = 32 * 1024 * 1024;
    private static final String USER_AGENT = "VibeM3U/0.4.4 (Android TV)";

    public EpgData download(URI epgUri) throws IOException {
        if (epgUri == null || epgUri.getScheme() == null) return EpgData.empty();

        HttpURLConnection connection = (HttpURLConnection) new URL(epgUri.toString()).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/xml, text/xml, text/plain, */*");
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("El servidor EPG respondió HTTP " + responseCode + ".");
            }
            byte[] bytes = readLimited(connection.getInputStream());
            return EpgParser.parse(new ByteArrayInputStream(bytes));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_EPG_BYTES) {
                    throw new IOException("La programación supera el límite de 32 MB.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
