package cl.streambox.tv;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the bundled resolver catalogue and installs validated data-only updates. */
public final class ResolverCatalogRepository {
    public static final String REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/resolver-catalog.json";
    private static final String ASSET_NAME = "resolver_catalog.json";
    private static final String EXPERIMENTAL_OVERLAY_ASSET =
            "resolver_vavoo_experimental.json";
    private static final String DIRECTORY_NAME = "resolver_catalog";
    private static final String INSTALLED_NAME = "resolver-catalog.json";

    private final Context context;
    private final AtomicFile installedFile;
    private final TokenHttpClient httpClient;

    public ResolverCatalogRepository(Context context) {
        this(context, new TokenHttpClient());
    }

    ResolverCatalogRepository(Context context, TokenHttpClient httpClient) {
        this.context = context.getApplicationContext();
        File directory = new File(this.context.getFilesDir(), DIRECTORY_NAME);
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
        installedFile = new AtomicFile(new File(directory, INSTALLED_NAME));
        this.httpClient = httpClient;
    }

    public ResolverCatalog load() throws IOException {
        if (installedFile.getBaseFile().isFile()) {
            try (InputStream input = installedFile.openRead()) {
                return applyBuildOverlay(ResolverCatalog.parse(readUtf8(input)));
            } catch (Exception ignored) {
                // An interrupted or incompatible data update must never stop
                // playback. The bundled catalogue remains the safe fallback.
                installedFile.delete();
            }
        }
        try (InputStream input = context.getAssets().open(ASSET_NAME)) {
            return applyBuildOverlay(ResolverCatalog.parse(readUtf8(input)));
        }
    }

    public UpdateResult downloadAndInstall() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        String json = httpClient.getText(REMOTE_CATALOG_URL, headers);
        ResolverCatalog downloaded = ResolverCatalog.parse(json);
        ResolverCatalog current = load();
        int comparison = compareVersions(downloaded.getVersion(), current.getVersion());
        if (comparison < 0) {
            throw new IOException("El catálogo remoto es más antiguo.");
        }
        if (comparison == 0) return new UpdateResult(current, false);

        FileOutputStream output = null;
        try {
            output = installedFile.startWrite();
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
            installedFile.finishWrite(output);
        } catch (IOException error) {
            if (output != null) installedFile.failWrite(output);
            throw error;
        }
        return new UpdateResult(applyBuildOverlay(downloaded), true);
    }

    private ResolverCatalog applyBuildOverlay(ResolverCatalog catalog) throws IOException {
        if (!BuildConfig.ENABLE_EXPERIMENTAL_VAVOO) return catalog;
        try (InputStream input = context.getAssets().open(EXPERIMENTAL_OVERLAY_ASSET)) {
            ResolverCatalog overlay = ResolverCatalog.parse(readUtf8(input));
            ResolverDefinition provider = overlay.getById("tvvoo");
            if (provider == null || !"vavoo".equals(provider.getEngine())) {
                throw new IOException("Configuración Vavoo experimental inválida.");
            }
            return catalog.replacingProvider(provider);
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > ResolverCatalog.MAX_CATALOG_BYTES) {
                throw new IOException("Catálogo demasiado grande.");
            }
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[._-]");
        String[] rightParts = right.split("[._-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            String leftPart = index < leftParts.length ? leftParts[index] : "0";
            String rightPart = index < rightParts.length ? rightParts[index] : "0";
            int result;
            try {
                result = Long.compare(Long.parseLong(leftPart), Long.parseLong(rightPart));
            } catch (NumberFormatException ignored) {
                result = leftPart.compareToIgnoreCase(rightPart);
            }
            if (result != 0) return result;
        }
        return 0;
    }

    public static final class UpdateResult {
        private final ResolverCatalog catalog;
        private final boolean changed;

        UpdateResult(ResolverCatalog catalog, boolean changed) {
            this.catalog = catalog;
            this.changed = changed;
        }

        public ResolverCatalog getCatalog() { return catalog; }
        public boolean isChanged() { return changed; }
    }
}
