package cl.streambox.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.util.LruCache;

import com.caverock.androidsvg.PreserveAspectRatio;
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
    private static final int MAX_RENDER_EDGE_PX = 4096;
    private static final int ALPHA_TRIM_THRESHOLD = 2;
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
        options.inPreferQualityOverSpeed = true;
        options.inScaled = false;
        options.inSampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidthPx,
                targetHeightPx
        );
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return decoded == null ? null : fitRasterToTarget(decoded, targetWidthPx, targetHeightPx);
    }

    private static Bitmap decodeSvg(byte[] bytes, int targetWidthPx, int targetHeightPx) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            SVG svg = SVG.getFromInputStream(input);
            int outputWidth = targetWidthPx > 0 ? targetWidthPx : documentSize(svg, true);
            int outputHeight = targetHeightPx > 0 ? targetHeightPx : documentSize(svg, false);
            configureSvgForViewport(svg, outputWidth, outputHeight);

            int renderWidth = targetWidthPx > 0
                    ? oversampledSize(targetWidthPx)
                    : outputWidth;
            int renderHeight = targetHeightPx > 0
                    ? oversampledSize(targetHeightPx)
                    : outputHeight;
            Bitmap rendered = Bitmap.createBitmap(
                    renderWidth,
                    renderHeight,
                    Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(rendered);
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            canvas.setDrawFilter(new PaintFlagsDrawFilter(
                    0,
                    Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
            ));
            svg.renderToCanvas(canvas, new RectF(0f, 0f, renderWidth, renderHeight));
            return targetWidthPx > 0
                    ? downsample(rendered, targetWidthPx, targetHeightPx)
                    : rendered;
        } catch (Exception error) {
            throw new IOException("No se pudo renderizar el logo SVG.", error);
        }
    }

    private static void configureSvgForViewport(SVG svg, int targetWidthPx, int targetHeightPx) {
        RectF viewBox = svg.getDocumentViewBox();
        if (!isValidRect(viewBox)) {
            float documentWidth = svg.getDocumentWidth();
            float documentHeight = svg.getDocumentHeight();
            float aspectRatio = svg.getDocumentAspectRatio();
            if (!isFinitePositive(aspectRatio)) {
                aspectRatio = targetWidthPx > 0 && targetHeightPx > 0
                        ? targetWidthPx / (float) targetHeightPx
                        : 1f;
            }
            if (!isFinitePositive(documentWidth)) {
                documentWidth = isFinitePositive(documentHeight)
                        ? documentHeight * aspectRatio
                        : DEFAULT_SVG_SIZE_PX * aspectRatio;
            }
            if (!isFinitePositive(documentHeight)) {
                documentHeight = documentWidth / aspectRatio;
            }
            svg.setDocumentViewBox(0f, 0f, documentWidth, documentHeight);
        }

        // AndroidSVG only scales a document reliably when its viewBox is present
        // and its root width/height are allowed to follow the render viewport.
        // This also fixes Illustrator/Inkscape SVGs that carry huge pixel sizes.
        svg.setDocumentWidth("100%");
        svg.setDocumentHeight("100%");
        svg.setDocumentPreserveAspectRatio(PreserveAspectRatio.LETTERBOX);
    }

    private static int documentSize(SVG svg, boolean width) {
        float value = width ? svg.getDocumentWidth() : svg.getDocumentHeight();
        if (value <= 0f || Float.isNaN(value) || Float.isInfinite(value)) {
            return DEFAULT_SVG_SIZE_PX;
        }
        return Math.max(1, Math.round(value));
    }

    private static Bitmap fitRasterToTarget(Bitmap source, int targetWidthPx, int targetHeightPx) {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) {
            return source;
        }

        Bitmap content = trimTransparentEdges(source);
        float scale = Math.min(
                1f,
                Math.min(
                        targetWidthPx / (float) content.getWidth(),
                        targetHeightPx / (float) content.getHeight()
                )
        );
        int width = Math.max(1, Math.round(content.getWidth() * scale));
        int height = Math.max(1, Math.round(content.getHeight() * scale));
        Bitmap scaled = downsample(content, width, height);

        Bitmap output = Bitmap.createBitmap(
                targetWidthPx,
                targetHeightPx,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        Paint paint = bitmapPaint();
        int left = (targetWidthPx - scaled.getWidth()) / 2;
        int top = (targetHeightPx - scaled.getHeight()) / 2;
        canvas.drawBitmap(scaled, left, top, paint);

        if (scaled != content && !scaled.isRecycled()) {
            scaled.recycle();
        }
        if (content != source && !content.isRecycled()) {
            content.recycle();
        }
        if (!source.isRecycled()) {
            source.recycle();
        }
        return output;
    }

    private static Bitmap trimTransparentEdges(Bitmap source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int left = sourceWidth;
        int top = sourceHeight;
        int right = -1;
        int bottom = -1;
        int[] row = new int[sourceWidth];

        for (int y = 0; y < sourceHeight; y++) {
            source.getPixels(row, 0, sourceWidth, 0, y, sourceWidth, 1);
            for (int x = 0; x < sourceWidth; x++) {
                if (((row[x] >>> 24) & 0xff) <= ALPHA_TRIM_THRESHOLD) {
                    continue;
                }
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }

        if (right < left || bottom < top
                || (left == 0 && top == 0 && right == sourceWidth - 1 && bottom == sourceHeight - 1)) {
            return source;
        }
        return Bitmap.createBitmap(
                source,
                left,
                top,
                right - left + 1,
                bottom - top + 1
        );
    }

    private static Bitmap downsample(Bitmap source, int targetWidthPx, int targetHeightPx) {
        Bitmap current = source;
        while (current.getWidth() > targetWidthPx * 2
                || current.getHeight() > targetHeightPx * 2) {
            int nextWidth = Math.max(targetWidthPx, current.getWidth() / 2);
            int nextHeight = Math.max(targetHeightPx, current.getHeight() / 2);
            Bitmap next = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true);
            if (current != source && !current.isRecycled()) {
                current.recycle();
            }
            current = next;
        }
        if (current.getWidth() != targetWidthPx || current.getHeight() != targetHeightPx) {
            Bitmap next = Bitmap.createScaledBitmap(
                    current,
                    targetWidthPx,
                    targetHeightPx,
                    true
            );
            if (current != source && !current.isRecycled()) {
                current.recycle();
            }
            current = next;
        }
        return current;
    }

    private static Paint bitmapPaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        return paint;
    }

    private static int oversampledSize(int size) {
        long value = (long) size * DECODE_OVERSAMPLE;
        return (int) Math.max(1, Math.min(MAX_RENDER_EDGE_PX, value));
    }

    private static boolean isValidRect(RectF value) {
        return value != null
                && isFinitePositive(value.width())
                && isFinitePositive(value.height())
                && Float.isFinite(value.left)
                && Float.isFinite(value.top);
    }

    private static boolean isFinitePositive(float value) {
        return value > 0f && Float.isFinite(value);
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
