package cl.streambox.tv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small disk cache for remote resources that supports HTTP revalidation.
 *
 * <p>The cache never treats a local file as permanently fresh. When metadata
 * is available, callers can ask the server for a conditional response and a
 * 304 keeps the existing bytes without downloading the body again. If a
 * server does not expose validators, the response body is compared before it
 * replaces the cached copy.</p>
 */
final class HttpResourceCache {
    static final class CachedResource {
        private final byte[] bytes;
        private final String etag;
        private final String lastModified;
        private final long checkedAtMillis;
        private final long fetchSequence;

        CachedResource(byte[] bytes, String etag, String lastModified) {
            this(bytes, etag, lastModified, 0L);
        }

        CachedResource(byte[] bytes, String etag, String lastModified, long checkedAtMillis) {
            this(bytes, etag, lastModified, checkedAtMillis, 0L);
        }

        CachedResource(
                byte[] bytes,
                String etag,
                String lastModified,
                long checkedAtMillis,
                long fetchSequence
        ) {
            this.bytes = bytes;
            this.etag = etag == null ? "" : etag;
            this.lastModified = lastModified == null ? "" : lastModified;
            this.checkedAtMillis = checkedAtMillis;
            this.fetchSequence = fetchSequence;
        }

        byte[] getBytes() {
            return bytes;
        }

        String getEtag() {
            return etag;
        }

        String getLastModified() {
            return lastModified;
        }

        long getCheckedAtMillis() {
            return checkedAtMillis;
        }

        /**
         * Monotonic sequence assigned to a network response by this cache.
         * Test and migration entries use zero and are always accepted by
         * {@link HttpResourceCache#commit(String, FetchResult, byte[])}.
         */
        long getFetchSequence() {
            return fetchSequence;
        }

        boolean isFresh(long nowMillis, long maxAgeMillis) {
            if (checkedAtMillis <= 0L || maxAgeMillis <= 0L) return false;
            if (etag.isBlank() && lastModified.isBlank()) return false;
            return nowMillis >= checkedAtMillis
                    && nowMillis - checkedAtMillis < maxAgeMillis;
        }
    }

    static final class FetchResult {
        private final CachedResource resource;
        private final boolean changed;
        private final boolean networkResponse;

        FetchResult(CachedResource resource, boolean changed, boolean networkResponse) {
            this.resource = resource;
            this.changed = changed;
            this.networkResponse = networkResponse;
        }

        CachedResource getResource() {
            return resource;
        }

        boolean isChanged() {
            return changed;
        }

        boolean hasNetworkResponse() {
            return networkResponse;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 25_000;

    private final File directory;
    private final File legacyDirectory;
    private final int maxFiles;
    private final long maxBytes;
    private final ConcurrentMap<String, Object> urlLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FutureTask<FetchResult>> inFlight =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> committedSequences = new ConcurrentHashMap<>();
    private final AtomicLong fetchSequence = new AtomicLong();
    private final Object trimLock = new Object();

    HttpResourceCache(Context context, String directoryName, int maxFiles, long maxBytes) {
        this(context, directoryName, maxFiles, maxBytes, true);
    }

    HttpResourceCache(
            Context context,
            String directoryName,
            int maxFiles,
            long maxBytes,
            boolean migrateLegacy
    ) {
        this(
                new File(context.getFilesDir(), directoryName),
                migrateLegacy ? new File(context.getCacheDir(), directoryName) : null,
                maxFiles,
                maxBytes
        );
    }

    HttpResourceCache(File directory, int maxFiles, long maxBytes) {
        this(directory, null, maxFiles, maxBytes);
    }

    private HttpResourceCache(
            File directory,
            File legacyDirectory,
            int maxFiles,
            long maxBytes
    ) {
        this.directory = directory;
        this.legacyDirectory = legacyDirectory;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
    }

    CachedResource readCached(String url, int maxResourceBytes) throws IOException {
        synchronized (lockFor(url)) {
            return readCachedLocked(url, maxResourceBytes);
        }
    }

    private CachedResource readCachedLocked(String url, int maxResourceBytes) throws IOException {
        File sourceDirectory = directory;
        File bodyFile = bodyFile(directory, url);
        if (!bodyFile.isFile() && legacyDirectory != null) {
            File legacyBodyFile = bodyFile(legacyDirectory, url);
            if (legacyBodyFile.isFile()) {
                sourceDirectory = legacyDirectory;
                bodyFile = legacyBodyFile;
            }
        }
        if (!bodyFile.isFile()) return null;
        if (bodyFile.length() <= 0 || bodyFile.length() > maxResourceBytes) {
            remove(url);
            return null;
        }

        try {
            byte[] bytes = readLimited(new FileInputStream(bodyFile), maxResourceBytes);
            Properties metadata = readMetadata(metadataFile(sourceDirectory, url));
            if (sourceDirectory != directory) {
                migrateLegacy(url, bytes, metadata);
            }
            bodyFile.setLastModified(System.currentTimeMillis());
            return new CachedResource(
                    bytes,
                    metadata.getProperty("etag", ""),
                    metadata.getProperty("last_modified", ""),
                    parseLong(metadata.getProperty("checked_at", "0"))
            );
        } catch (IOException error) {
            remove(url);
            return null;
        }
    }

    FetchResult fetch(
            String url,
            int maxResourceBytes,
            String userAgent,
            String accept
    ) throws IOException {
        FutureTask<FetchResult> task = new FutureTask<>(
                () -> fetchNetwork(url, maxResourceBytes, userAgent, accept)
        );
        FutureTask<FetchResult> active = inFlight.putIfAbsent(url, task);
        if (active != null) {
            return awaitFetch(active);
        }

        try {
            task.run();
            return awaitFetch(task);
        } finally {
            removeInFlight(url, task);
        }
    }

    /**
     * Performs one request after taking a short cache snapshot. The network is
     * intentionally outside every cache lock: a retained server must not stop
     * a cached read for this URL (or any other local cache operation).
     */
    private FetchResult fetchNetwork(
            String url,
            int maxResourceBytes,
            String userAgent,
            String accept
    ) throws IOException {
        CachedResource cached = readCached(url, maxResourceBytes);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("User-Agent", userAgent);
            if (cached != null) {
                if (!cached.getEtag().isBlank()) {
                    connection.setRequestProperty("If-None-Match", cached.getEtag());
                }
                if (!cached.getLastModified().isBlank()) {
                    connection.setRequestProperty("If-Modified-Since", cached.getLastModified());
                }
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && cached != null) {
                CachedResource validated = new CachedResource(
                        cached.getBytes(),
                        cached.getEtag(),
                        cached.getLastModified(),
                        System.currentTimeMillis()
                );
                try {
                    synchronized (lockFor(url)) {
                        writeMetadata(url, metadataFor(validated));
                    }
                } catch (IOException ignored) {
                    // The validated body remains usable even if only the
                    // freshness marker could not be persisted.
                }
                return new FetchResult(validated, false, false);
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("El servidor respondió HTTP " + responseCode + ".");
            }

            byte[] bytes = readLimited(connection.getInputStream(), maxResourceBytes);
            String etag = valueOrEmpty(connection.getHeaderField("ETag"));
            String lastModified = valueOrEmpty(connection.getHeaderField("Last-Modified"));
            CachedResource fresh = new CachedResource(
                    bytes,
                    etag,
                    lastModified,
                    System.currentTimeMillis(),
                    fetchSequence.incrementAndGet()
            );
            boolean changed = cached == null || !Arrays.equals(cached.getBytes(), bytes);
            return new FetchResult(fresh, changed, true);
        } catch (IOException error) {
            if (cached != null) {
                // A stale-but-valid copy is preferable to a blank screen when
                // the source is temporarily unavailable.
                return new FetchResult(cached, false, false);
            }
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static FetchResult awaitFetch(FutureTask<FetchResult> task) throws IOException {
        try {
            return task.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("La solicitud de caché fue interrumpida.", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException("No se pudo consultar el recurso.", cause);
        }
    }

    void commit(String url, FetchResult result, byte[] bytes) throws IOException {
        synchronized (lockFor(url)) {
            commitLocked(url, result, bytes);
        }
    }

    private void commitLocked(String url, FetchResult result, byte[] bytes) throws IOException {
        if (result == null || !result.hasNetworkResponse()) return;
        CachedResource resource = result.getResource();
        if (resource == null || bytes == null || bytes.length == 0) return;

        long sequence = resource.getFetchSequence();
        Long committed = committedSequences.get(url);
        if (sequence > 0L && committed != null && sequence < committed) {
            // A slower parser/decoder must not publish an older response after
            // a newer response has already reached disk.
            return;
        }

        writeBody(url, bytes);

        writeMetadata(url, metadataFor(resource));
        if (sequence > 0L) committedSequences.put(url, sequence);
        trim();
    }

    /**
     * Replaces only the cached body while preserving its HTTP validators.
     *
     * <p>This is used to migrate entries written by an older version before
     * they can be parsed or returned to the player. It deliberately does not
     * create a network result, so it cannot manufacture or persist resolver
     * credentials as if they were a fresh response.</p>
     */
    void rewriteCached(String url, byte[] bytes) throws IOException {
        synchronized (lockFor(url)) {
            rewriteCachedLocked(url, bytes);
        }
    }

    private void rewriteCachedLocked(String url, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            removeLocked(url);
            return;
        }
        writeBody(url, bytes);
        trim();
    }

    void remove(String url) {
        synchronized (lockFor(url)) {
            removeLocked(url);
        }
    }

    private void removeLocked(String url) {
        //noinspection ResultOfMethodCallIgnored
        bodyFile(directory, url).delete();
        //noinspection ResultOfMethodCallIgnored
        metadataFile(directory, url).delete();
        if (legacyDirectory != null) {
            //noinspection ResultOfMethodCallIgnored
            bodyFile(legacyDirectory, url).delete();
            //noinspection ResultOfMethodCallIgnored
            metadataFile(legacyDirectory, url).delete();
        }
    }

    private static File bodyFile(File directory, String url) {
        return new File(directory, cacheKey(url) + ".body");
    }

    private static File metadataFile(File directory, String url) {
        return new File(directory, cacheKey(url) + ".properties");
    }

    private void writeBody(String url, byte[] bytes) throws IOException {
        ensureDirectory();
        File target = bodyFile(directory, url);
        File temporary = temporaryFile(target);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        replaceFile(temporary, target);
        target.setLastModified(System.currentTimeMillis());
    }

    private void writeMetadata(String url, Properties metadata) throws IOException {
        ensureDirectory();
        File metadataTarget = metadataFile(directory, url);
        File metadataTemporary = temporaryFile(metadataTarget);
        try (FileOutputStream output = new FileOutputStream(metadataTemporary);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            metadata.store(writer, "VibeM3U resource cache");
            writer.flush();
            output.getFD().sync();
        }
        replaceFile(metadataTemporary, metadataTarget);
    }

    private void ensureDirectory() throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.exists() && directory.mkdirs()) return;
        throw new IOException("No se pudo crear la caché local.");
    }

    private static File temporaryFile(File target) {
        return new File(
                target.getParentFile(),
                target.getName()
                        + ".tmp-"
                        + Long.toHexString(System.nanoTime())
                        + "-"
                        + Long.toHexString(Thread.currentThread().getId())
        );
    }

    private static Properties metadataFor(CachedResource resource) {
        Properties metadata = new Properties();
        if (!resource.getEtag().isBlank()) metadata.setProperty("etag", resource.getEtag());
        if (!resource.getLastModified().isBlank()) {
            metadata.setProperty("last_modified", resource.getLastModified());
        }
        long checkedAtMillis = resource.getCheckedAtMillis() > 0L
                ? resource.getCheckedAtMillis()
                : System.currentTimeMillis();
        metadata.setProperty("checked_at", Long.toString(checkedAtMillis));
        return metadata;
    }

    private void migrateLegacy(String url, byte[] bytes, Properties metadata) {
        try {
            writeMetadata(url, metadata);
            writeBody(url, bytes);
            trim();
        } catch (IOException ignored) {
            // The legacy copy remains usable for this read. A later successful
            // network response will recreate the persistent entry.
        }
    }

    private static Properties readMetadata(File file) throws IOException {
        Properties properties = new Properties();
        if (!file.isFile()) return properties;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        }
        return properties;
    }

    private void trim() {
        synchronized (trimLock) {
            trimLocked();
        }
    }

    private void trimLocked() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".body"));
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
            if (index >= maxFiles || totalBytes > maxBytes) {
                String name = file.getName();
                String key = name.substring(0, name.length() - ".body".length());
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                new File(directory, key + ".properties").delete();
            }
        }
    }

    private static void replaceFile(File temporary, File target) throws IOException {
        // renameTo is an atomic replacement on the Android/Linux filesystem
        // used by the app. The delete-and-retry path is kept for JVM tests and
        // filesystems that refuse to replace an existing file in place.
        if (temporary.renameTo(target)) return;
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("No se pudo reemplazar una entrada de caché.");
        }
        if (!temporary.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("No se pudo guardar una entrada de caché.");
        }
    }

    private Object lockFor(String url) {
        String key = url == null ? "" : url;
        Object existing = urlLocks.get(key);
        if (existing != null) return existing;
        Object created = new Object();
        Object raced = urlLocks.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private void removeInFlight(String url, FutureTask<FetchResult> task) {
        if (inFlight.get(url) == task) inFlight.remove(url);
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IOException("El recurso supera el límite de caché.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
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
}
