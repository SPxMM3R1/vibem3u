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
import java.util.Map;
import java.util.Set;

/**
 * Network and disk cache for the public TvVoo manifest/catalogue.
 *
 * <p>Callers should invoke this class from a worker thread. A failed refresh
 * leaves both the previous cache and the independent selection store intact.
 * URLs and response bodies are catalogue metadata only; resolved stream URLs
 * are deliberately never cached here.</p>
 */
public final class TvVooCatalogRepository {
    public static final String MANIFEST_URL = "https://tvvoo.hayd.uk/manifest.json";
    private static final String CATALOG_URL_PREFIX = "https://tvvoo.hayd.uk/catalog/tv/";
    private static final int MANIFEST_MAX_BYTES = TvVooCatalogManifest.MAX_BYTES;
    private static final int CATALOG_MAX_BYTES = TvVooCatalog.MAX_BYTES;
    private static final Set<String> ALLOWED_HOSTS =
            Collections.unmodifiableSet(new HashSet<>(Collections.singletonList("tvvoo.hayd.uk")));

    private final Context context;
    private final TokenHttpClient httpClient;
    private final File cacheDirectory;

    public TvVooCatalogRepository(Context context) {
        this(context, new TokenHttpClient(6_000, 15_000));
    }

    TvVooCatalogRepository(Context context, TokenHttpClient httpClient) {
        if (context == null) throw new IllegalArgumentException("context");
        this.context = context.getApplicationContext();
        this.httpClient = httpClient == null ? new TokenHttpClient() : httpClient;
        this.cacheDirectory = new File(this.context.getFilesDir(), "tvvoo-catalog");
    }

    /** Network-first load with a stale-cache fallback. */
    public TvVooCatalogManifest loadManifest() throws IOException {
        IOException networkError;
        try {
            String json = fetch(MANIFEST_URL, MANIFEST_MAX_BYTES);
            TvVooCatalogManifest manifest = TvVooCatalogManifest.parse(json);
            writeCacheBestEffort("manifest.json", json);
            return manifest;
        } catch (IOException error) {
            networkError = error;
        }
        try {
            return TvVooCatalogManifest.parse(readCache("manifest.json"));
        } catch (IOException cacheError) {
            cacheError.addSuppressed(networkError);
            throw cacheError;
        }
    }

    /** Returns the last valid manifest without making a network request. */
    public TvVooCatalogManifest readCachedManifest() throws IOException {
        return TvVooCatalogManifest.parse(readCache("manifest.json"));
    }

    /** Network-first load with a stale-cache fallback for one country. */
    public TvVooCatalog loadCatalog(TvVooCatalogManifest.Catalog catalog) throws IOException {
        if (catalog == null) throw new IOException("Catálogo TvVoo ausente.");
        String id = catalog.getId();
        String url = CATALOG_URL_PREFIX + id + ".json";
        String fileName = "catalog_" + safeFilePart(id) + ".json";
        IOException networkError;
        try {
            String json = fetch(url, CATALOG_MAX_BYTES);
            TvVooCatalog parsed = TvVooCatalog.parse(json, id, catalog.getCountryKey());
            writeCacheBestEffort(fileName, json);
            return parsed;
        } catch (IOException error) {
            networkError = error;
        }
        try {
            return TvVooCatalog.parse(readCache(fileName), id, catalog.getCountryKey());
        } catch (IOException cacheError) {
            cacheError.addSuppressed(networkError);
            throw cacheError;
        }
    }

    public TvVooCatalog readCachedCatalog(TvVooCatalogManifest.Catalog catalog)
            throws IOException {
        if (catalog == null) throw new IOException("Catálogo TvVoo ausente.");
        String fileName = "catalog_" + safeFilePart(catalog.getId()) + ".json";
        return TvVooCatalog.parse(
                readCache(fileName),
                catalog.getId(),
                catalog.getCountryKey()
        );
    }

    private String fetch(String url, int maxBytes) throws IOException {
        Map<String, String> headers = Collections.singletonMap("Accept", "application/json");
        TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                url,
                headers,
                maxBytes,
                null,
                ALLOWED_HOSTS
        );
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private String readCache(String name) throws IOException {
        File file = new File(cacheDirectory, name);
        if (!file.isFile()) throw new IOException("No hay caché TvVoo.");
        long length = file.length();
        if (length <= 0 || length > CATALOG_MAX_BYTES) throw new IOException("Caché TvVoo inválida.");
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != bytes.length) throw new IOException("Caché TvVoo incompleta.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeCacheBestEffort(String name, String json) {
        try {
            writeCache(name, json);
        } catch (IOException ignored) {
            // A valid network response remains usable when storage is full or unavailable.
        }
    }

    private void writeCache(String name, String json) throws IOException {
        if (json == null) throw new IOException("Respuesta TvVoo vacía.");
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs() && !cacheDirectory.isDirectory()) {
            throw new IOException("No se pudo crear la caché TvVoo.");
        }
        File target = new File(cacheDirectory, name);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(bytes);
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomic.failWrite(output);
            throw new IOException("No se pudo actualizar la caché TvVoo.", error);
        }
    }

    private static String safeFilePart(String value) throws IOException {
        if (value == null || !value.matches("[a-z0-9_-]{1,64}")) {
            throw new IOException("ID de catálogo TvVoo inválido.");
        }
        return value;
    }
}
