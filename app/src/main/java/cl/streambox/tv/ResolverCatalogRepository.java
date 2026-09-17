package cl.streambox.tv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the resolver catalogue bundled with the current APK. */
public final class ResolverCatalogRepository {
    private static final String ASSET_NAME = "resolver_catalog.json";

    private final Context context;

    public ResolverCatalogRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public ResolverCatalog load() throws IOException {
        try (InputStream input = context.getAssets().open(ASSET_NAME)) {
            return ResolverCatalog.parse(readUtf8(input));
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

}
