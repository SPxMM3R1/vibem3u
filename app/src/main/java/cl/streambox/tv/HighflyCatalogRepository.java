package cl.streambox.tv;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Loads Highfly's live metadata catalog and keeps a token-free disk cache. */
public final class HighflyCatalogRepository {
    private static final int MANIFEST_MAX_BYTES = 256 * 1024;
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Collections.singletonList("sports.highfly.to"))
    );

    private final TokenHttpClient httpClient;
    private final File cacheFile;

    public HighflyCatalogRepository(Context context) {
        this(context, new TokenHttpClient(6_000, 15_000));
    }

    HighflyCatalogRepository(Context context, TokenHttpClient httpClient) {
        if (context == null) throw new IllegalArgumentException("context");
        this.httpClient = httpClient == null ? new TokenHttpClient() : httpClient;
        File directory = new File(context.getApplicationContext().getFilesDir(), "highfly-catalog");
        this.cacheFile = new File(directory, "live.json");
    }

    /** Network-first refresh with a stale metadata-only fallback. */
    public HighflyCatalog loadCatalog(String configuredManifestUrl) throws IOException {
        IOException networkError;
        try {
            String manifestUrl = normalizedManifest(configuredManifestUrl);
            String manifest = fetch(manifestUrl, MANIFEST_MAX_BYTES);
            if (!ResolverPayloadParsers.isHighflyStreamManifest(manifest)) {
                throw new IOException("El manifiesto Highfly no publica el recurso stream.");
            }
            String catalogUrl = HighflyStreamResolver.catalogResourceUri(
                    java.net.URI.create(manifestUrl)
            ).toString();
            String catalogJson = fetch(catalogUrl, HighflyCatalog.MAX_BYTES);
            HighflyCatalog catalog = HighflyCatalog.parse(catalogJson);
            writeCacheBestEffort(catalog.toJson());
            return catalog;
        } catch (IOException error) {
            networkError = error;
        }
        try {
            return readCachedCatalog();
        } catch (IOException cacheError) {
            cacheError.addSuppressed(networkError);
            throw cacheError;
        }
    }

    public HighflyCatalog readCachedCatalog() throws IOException {
        File file = cacheFile;
        if (!file.isFile()) throw new IOException("No hay caché Highfly.");
        long length = file.length();
        if (length <= 0 || length > HighflyCatalog.MAX_BYTES) {
            throw new IOException("Caché Highfly inválida.");
        }
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != bytes.length) throw new IOException("Caché Highfly incompleta.");
        }
        return HighflyCatalog.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private String fetch(String url, int maximumBytes) throws IOException {
        TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                url,
                Collections.singletonMap("Accept", "application/json"),
                maximumBytes,
                null,
                ALLOWED_HOSTS
        );
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private void writeCacheBestEffort(String json) {
        try {
            if (!cacheFile.getParentFile().exists()
                    && !cacheFile.getParentFile().mkdirs()
                    && !cacheFile.getParentFile().isDirectory()) return;
            AtomicFile atomic = new AtomicFile(cacheFile);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(json.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
                atomic.finishWrite(output);
            } catch (IOException error) {
                if (output != null) atomic.failWrite(output);
            }
        } catch (Exception ignored) {
            // Playback and the selector remain usable if storage is unavailable.
        }
    }

    private static String normalizedManifest(String value) throws IOException {
        String candidate = AppStrings.isBlank(value)
                ? HighflyStreamResolver.DEFAULT_MANIFEST_URL
                : value.trim();
        if (!HighflyStreamResolver.isAllowedManifestUrl(candidate)) {
            throw new IOException("Manifiesto Highfly no permitido.");
        }
        return candidate;
    }
}
