package cl.streambox.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
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

public final class ChannelLogoCache {
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_LOGO_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DISK_FILES = 96;
    private static final long MAX_DISK_BYTES = 24L * 1024L * 1024L;
    private static final String USER_AGENT = "VibeM3U/0.2 (Android TV)";

    private final LruCache<String, Bitmap> memoryCache;
    private final File diskDirectory;

    public ChannelLogoCache(Context context) {
        int availableKb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024L);
        int memoryCacheKb = Math.max(4 * 1024, Math.min(16 * 1024, availableKb / 16));
        memoryCache = new LruCache<>(memoryCacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getByteCount() / 1024);
            }
        };
        diskDirectory = new File(context.getCacheDir(), "channel_logos");
        if (!diskDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            diskDirectory.mkdirs();
        }
    }

    public synchronized Bitmap load(URI logoUri) throws IOException {
        String url = logoUri.toString();
        Bitmap memoryBitmap = memoryCache.get(url);
        if (memoryBitmap != null && !memoryBitmap.isRecycled()) {
            return memoryBitmap;
        }

        File diskFile = new File(diskDirectory, cacheKey(url) + ".img");
        Bitmap diskBitmap = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
        if (diskBitmap != null) {
            //noinspection ResultOfMethodCallIgnored
            diskFile.setLastModified(System.currentTimeMillis());
            memoryCache.put(url, diskBitmap);
            return diskBitmap;
        }
        if (diskFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            diskFile.delete();
        }

        byte[] bytes = download(url);
        Bitmap downloadedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (downloadedBitmap == null) {
            throw new IOException("El logo no tiene un formato de imagen compatible.");
        }

        writeToDisk(diskFile, bytes);
        trimDiskCache();
        memoryCache.put(url, downloadedBitmap);
        return downloadedBitmap;
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(true);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.8");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " al descargar el logo.");
            }
            return readLimited(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_LOGO_BYTES) {
                    throw new IOException("El logo supera el límite de 2 MB.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void writeToDisk(File target, byte[] bytes) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
        }
        if (target.exists() && !target.delete()) {
            // El archivo válido anterior seguirá funcionando si no puede reemplazarse.
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private void trimDiskCache() {
        File[] files = diskDirectory.listFiles((directory, name) -> name.endsWith(".img"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, new Comparator<>() {
            @Override
            public int compare(File first, File second) {
                long firstModified = first.lastModified();
                long secondModified = second.lastModified();
                if (firstModified == secondModified) return 0;
                return firstModified < secondModified ? 1 : -1;
            }
        });
        long totalBytes = 0;
        for (int index = 0; index < files.length; index++) {
            File file = files[index];
            totalBytes += file.length();
            if (index >= MAX_DISK_FILES || totalBytes > MAX_DISK_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static String cacheKey(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
