package cl.streambox.tv;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class EpgRepository {
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_EPG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 8;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final String USER_AGENT = "VibeM3U/0.4 (Android TV)";

    private final File cacheDirectory;

    public EpgRepository(Context context) {
        cacheDirectory = new File(context.getCacheDir(), "epg");
        if (!cacheDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDirectory.mkdirs();
        }
    }

    public EpgData loadCached(URI epgUri) {
        File cacheFile = cacheFile(epgUri);
        if (cacheFile == null || !cacheFile.isFile()) return null;

        try (InputStream input = new FileInputStream(cacheFile)) {
            EpgData cached = EpgParser.parse(input);
            // A recently used cache should survive trimming longer than stale entries.
            //noinspection ResultOfMethodCallIgnored
            cacheFile.setLastModified(System.currentTimeMillis());
            return cached;
        } catch (IOException ignored) {
            // Una caché dañada no debe impedir descargar la programación nueva.
            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();
            return null;
        }
    }

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
            EpgData downloaded = EpgParser.parse(new ByteArrayInputStream(bytes));
            saveCached(epgUri, bytes);
            return downloaded;
        } finally {
            connection.disconnect();
        }
    }

    private void saveCached(URI epgUri, byte[] bytes) {
        File target = cacheFile(epgUri);
        if (target == null) return;

        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        boolean movedPrevious = false;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
            }

            if (backup.exists() && !backup.delete()) {
                throw new IOException("No se pudo preparar la caché EPG.");
            }
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw new IOException("No se pudo conservar la caché EPG anterior.");
                }
                movedPrevious = true;
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("No se pudo guardar la caché EPG nueva.");
            }
            if (movedPrevious) {
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
            trimCache();
        } catch (IOException ignored) {
            if (movedPrevious && !target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private void trimCache() {
        File[] files = cacheDirectory.listFiles((directory, name) -> name.endsWith(".xml"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, new Comparator<>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });

        long totalBytes = 0;
        for (int index = 0; index < files.length; index++) {
            File file = files[index];
            totalBytes += file.length();
            if (index >= MAX_CACHE_FILES || totalBytes > MAX_CACHE_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private File cacheFile(URI epgUri) {
        if (epgUri == null || epgUri.getScheme() == null) return null;
        return new File(cacheDirectory, cacheKey(epgUri.toString()) + ".xml");
    }

    private static String cacheKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
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
