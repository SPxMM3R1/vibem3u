package cl.streambox.tv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import android.content.Context;

/**
 * Reads the public playback configuration; redirects stay pinned to raw GitHub.
 *
 * <p>Desde la 0.5.85 cada documento descargado bien se guarda en disco: la app arranca al
 * instante con el último catálogo conocido y lo renueva en segundo plano. Solo se guarda la
 * configuración pública publicada por Lista M3U (sin tokens ni URLs firmadas).</p>
 */
final class PublishedPlaybackCatalogRepository {
    static final String LAYOUT_URL =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/data/channel-editor-layout.json";
    /** Enlaces directos Highfly que el runner renueva cada 30 minutos. */
    static final String HIGHFLY_LINKS_URL =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/data/highfly-live.json";
    static final String TVVOO_VARIANTS_URL =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/data/tvvoo-variantes.json";
    static final String LAYOUT_FILE = "layout.json";
    static final String HIGHFLY_LINKS_FILE = "highfly-live.json";
    static final String TVVOO_VARIANTS_FILE = "tvvoo-variantes.json";
    private static final String CACHE_DIR = "published_catalog";
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Collections.singletonList("raw.githubusercontent.com"))
    );
    private final TokenHttpClient httpClient;
    /** Null en pruebas sin almacenamiento: entonces no se guarda nada. */
    private final File cacheDir;

    PublishedPlaybackCatalogRepository(Context context) {
        this(context, new TokenHttpClient(6_000, 15_000));
    }

    PublishedPlaybackCatalogRepository(Context context, TokenHttpClient httpClient) {
        if (context == null) throw new IllegalArgumentException("context");
        this.httpClient = httpClient == null ? new TokenHttpClient(6_000, 15_000) : httpClient;
        Context application = context.getApplicationContext();
        File filesDir = (application == null ? context : application).getFilesDir();
        this.cacheDir = filesDir == null ? null : new File(filesDir, CACHE_DIR);
    }

    static void clearRetiredLocalSelections(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        for (String preferenceName : new String[] {
                "tvvoo_selection",
                "highfly_selection",
                "published_playback_catalog"
        }) {
            application.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();
        }
    }

    /** Descarga el catálogo (layout del editor), lo guarda y devuelve el documento crudo. */
    String fetchLayoutDocument() throws IOException {
        String document = download(LAYOUT_URL, PublishedPlaybackCatalog.MAX_BYTES);
        // Solo se guarda un documento válido: uno roto no reemplaza al último bueno.
        PublishedPlaybackCatalog.parse(document);
        store(LAYOUT_FILE, document);
        return document;
    }

    PublishedPlaybackCatalog refresh() throws IOException {
        return PublishedPlaybackCatalog.parse(fetchLayoutDocument());
    }

    /** Descarga los enlaces directos Highfly; un fallo solo deja el resolutor de siempre. */
    String fetchHighflyLinks() throws IOException {
        String document = download(HIGHFLY_LINKS_URL, PublishedHighflyLinks.MAX_BYTES);
        store(HIGHFLY_LINKS_FILE, document);
        return document;
    }

    String fetchTvVooVariants() throws IOException {
        String document = download(TVVOO_VARIANTS_URL, PublishedTvVooVariants.MAX_BYTES);
        store(TVVOO_VARIANTS_FILE, document);
        return document;
    }

    /** Último documento guardado con ese nombre, o null si no hay. */
    String cached(String name) {
        if (cacheDir == null) return null;
        File file = new File(cacheDir, name);
        if (!file.isFile() || file.length() <= 0L || file.length() > PublishedPlaybackCatalog.MAX_BYTES) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private String download(String url, int maxBytes) throws IOException {
        TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                url,
                Collections.singletonMap("Accept", "application/json"),
                maxBytes,
                null,
                ALLOWED_HOSTS
        );
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    /** Escritura atómica: un corte de luz no deja un archivo a medias. */
    private void store(String name, String document) {
        if (cacheDir == null || document == null) return;
        try {
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return;
            File temporary = new File(cacheDir, name + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(document.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            File target = new File(cacheDir, name);
            if (!temporary.renameTo(target)) {
                Files.deleteIfExists(target.toPath());
                if (!temporary.renameTo(target)) Files.deleteIfExists(temporary.toPath());
            }
        } catch (IOException | RuntimeException ignored) {
            // Sin caché la app funciona como antes: espera la red.
        }
    }
}
