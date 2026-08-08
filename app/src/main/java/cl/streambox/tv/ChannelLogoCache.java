package cl.streambox.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.LruCache;

import com.caverock.androidsvg.SVG;

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

public final class ChannelLogoCache {
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_LOGO_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DISK_FILES = 96;
    private static final long MAX_DISK_BYTES = 24L * 1024L * 1024L;
    private static final int DECODE_OVERSAMPLE = 4;
    private static final int DEFAULT_SVG_SIZE_PX = 512;
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
        return load(logoUri, 0, 0);
    }

    public synchronized Bitmap load(URI logoUri, int targetWidthPx, int targetHeightPx) throws IOException {
        String url = logoUri.toString();
        String memoryKey = displayCacheKey(url, targetWidthPx, targetHeightPx);
        Bitmap memoryBitmap = memoryCache.get(memoryKey);
        if (memoryBitmap != null && !memoryBitmap.isRecycled()) {
            return memoryBitmap;
        }

        File diskFile = new File(diskDirectory, cacheKey(url) + ".img");
        if (diskFile.exists()) {
            try {
                Bitmap diskBitmap = decodeLogo(
                        readLimited(new FileInputStream(diskFile)),
                        url,
                        targetWidthPx,
                        targetHeightPx
                );
                if (diskBitmap != null) {
                    //noinspection ResultOfMethodCallIgnored
                    diskFile.setLastModified(System.currentTimeMillis());
                    memoryCache.put(memoryKey, diskBitmap);
                    return diskBitmap;
                }
            } catch (IOException ignored) {
                // Se vuelve a descargar cuando el caché anterior no se puede decodificar.
            }
            //noinspection ResultOfMethodCallIgnored
            diskFile.delete();
        }

        byte[] bytes = download(url);
        Bitmap downloadedBitmap = decodeLogo(bytes, url, targetWidthPx, targetHeightPx);
        if (downloadedBitmap == null) {
            throw new IOException("El logo no tiene un formato de imagen compatible.");
        }

        writeToDisk(diskFile, bytes);
        trimDiskCache();
        memoryCache.put(memoryKey, downloadedBitmap);
        return downloadedBitmap;
    }

    private static Bitmap decodeLogo(byte[] bytes, String url, int targetWidthPx, int targetHeightPx)
            throws IOException {
        if (looksLikeSvg(bytes, url)) {
            return decodeSvg(bytes, targetWidthPx, targetHeightPx);
        }
        return decodeBitmap(bytes, targetWidthPx, targetHeightPx);
    }

    private static Bitmap decodeBitmap(byte[] bytes, int targetWidthPx, int targetHeightPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = true;
        options.inScaled = false;
        options.inSampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidthPx,
                targetHeightPx
        );
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return decoded == null ? null : scaleToFit(decoded, targetWidthPx, targetHeightPx);
    }

    private static Bitmap decodeSvg(byte[] bytes, int targetWidthPx, int targetHeightPx) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            SVG svg = SVG.getFromInputStream(input);
            int width = targetWidthPx > 0 ? targetWidthPx : documentSize(svg, true);
            int height = targetHeightPx > 0 ? targetHeightPx : documentSize(svg, false);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            svg.renderToCanvas(canvas, new RectF(0f, 0f, width, height));
            return bitmap;
        } catch (Exception error) {
            throw new IOException("No se pudo renderizar el logo SVG.", error);
        }
    }

    private static int documentSize(SVG svg, boolean width) {
        float value = width ? svg.getDocumentWidth() : svg.getDocumentHeight();
        if (value <= 0f || Float.isNaN(value) || Float.isInfinite(value)) {
            return DEFAULT_SVG_SIZE_PX;
        }
        return Math.max(1, Math.round(value));
    }

    private static Bitmap scaleToFit(Bitmap source, int targetWidthPx, int targetHeightPx) {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) {
            return source;
        }

        float scale = Math.min(
                1f,
                Math.min(
                        targetWidthPx / (float) source.getWidth(),
                        targetHeightPx / (float) source.getHeight()
                )
        );
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        if (width == source.getWidth() && height == source.getHeight()) {
            return source;
        }

        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    private static int calculateSampleSize(
            int sourceWidth,
            int sourceHeight,
            int targetWidthPx,
            int targetHeightPx
    ) {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) {
            return 1;
        }

        int desiredWidth = Math.max(1, targetWidthPx * DECODE_OVERSAMPLE);
        int desiredHeight = Math.max(1, targetHeightPx * DECODE_OVERSAMPLE);
        int sampleSize = 1;
        while (sourceWidth / (sampleSize * 2) >= desiredWidth
                && sourceHeight / (sampleSize * 2) >= desiredHeight) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static boolean looksLikeSvg(byte[] bytes, String url) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.endsWith(".svg") || lowerUrl.contains(".svg?")) {
            return true;
        }
        int length = Math.min(bytes.length, 4096);
        String prefix = new String(bytes, 0, length, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return prefix.contains("<svg");
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

    private static String displayCacheKey(String url, int targetWidthPx, int targetHeightPx) {
        return url + "#" + targetWidthPx + "x" + targetHeightPx;
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
