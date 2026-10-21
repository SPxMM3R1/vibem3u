package cl.streambox.tv;

import android.content.Context;

import java.io.IOException;
import java.net.URI;

public final class EpgRepository {
    // Lista M3U publicó el 2026-10-21 una EPG de ~51 MB al sumar Lista 2.
    // El límite anterior (32 MB) descartaba el archivo completo y dejaba canales
    // como el 1005 sin guía. 64 MB cubre el archivo y la caché conserva 3 versiones.
    private static final int MAX_EPG_BYTES = 64 * 1024 * 1024;
    private static final long REVALIDATION_INTERVAL_MS = 5L * 60L * 1000L;
    private static final String USER_AGENT = AppUserAgent.VALUE;
    private static final String ACCEPT = "application/xml, text/xml, text/plain, */*";

    private final HttpResourceCache cache;
    private final EpgSnapshotCache snapshotCache;

    public EpgRepository(Context context) {
        cache = new HttpResourceCache(
                context,
                "epg_cache",
                3,
                160L * 1024L * 1024L
        );
        snapshotCache = new EpgSnapshotCache(
                new java.io.File(context.getFilesDir(), "epg_snapshots_v1")
        );
    }

    /**
     * Guía guardada (0.6.26): la instantánea ya procesada se valida con la huella guardada
     * en la caché, sin volver a leer el XML; solo si falta se lee el XML desde el disco.
     */
    public EpgData loadCached(URI epgUri) throws IOException {
        String url = validUrl(epgUri);
        if (url == null) return null;
        HttpResourceCache.EntryInfo entry = cache.entryInfo(url, MAX_EPG_BYTES);
        if (entry == null) return null;
        EpgData snapshot = snapshotCache.loadDigest(url, entry.sha256);
        if (snapshot != null) return snapshot;
        try {
            EpgData parsed = parseFile(entry.body);
            snapshotCache.storeDigest(url, entry.sha256, parsed);
            return parsed;
        } catch (IOException error) {
            cache.remove(url);
            return null;
        }
    }

    /**
     * Revisa la guía en la red. Si no cambió (revisión reciente, 304 o misma huella) no la
     * vuelve a procesar: devuelve sin cambios y, solo si {@code needData}, la guardada.
     */
    public LoadResult downloadIfChanged(URI epgUri, boolean needData) throws IOException {
        String url = validUrl(epgUri);
        if (url == null) return new LoadResult(EpgData.empty(), false);
        HttpResourceCache.EntryInfo entry = cache.entryInfo(url, MAX_EPG_BYTES);
        if (entry != null && entry.isFresh(System.currentTimeMillis(), REVALIDATION_INTERVAL_MS)) {
            return new LoadResult(needData ? loadCached(epgUri) : null, false);
        }
        HttpResourceCache.StreamFetch fetched = cache.fetchToFile(url, MAX_EPG_BYTES, USER_AGENT, ACCEPT);
        if (!fetched.isChanged()) {
            return new LoadResult(needData ? loadCached(epgUri) : null, false);
        }
        EpgData data;
        try {
            data = parseFile(fetched.temporary);
        } catch (IOException error) {
            HttpResourceCache.discard(fetched);
            throw error;
        }
        cache.commitFile(url, fetched);
        snapshotCache.storeDigest(url, fetched.sha256, data);
        return new LoadResult(data, true);
    }

    public LoadResult downloadIfChanged(URI epgUri) throws IOException {
        return downloadIfChanged(epgUri, true);
    }

    /** Compatibility entry point for callers that do not need change metadata. */
    public EpgData download(URI epgUri) throws IOException {
        return downloadIfChanged(epgUri, true).getData();
    }

    private static EpgData parseFile(java.io.File file) throws IOException {
        try (java.io.InputStream input = new java.io.BufferedInputStream(
                new java.io.FileInputStream(file), 64 * 1024)) {
            // 0.6.27: acepta la guía comprimida con gzip (se reconoce por sus dos primeros bytes).
            input.mark(2);
            int first = input.read();
            int second = input.read();
            input.reset();
            if (first == 0x1f && second == 0x8b) {
                try (java.io.InputStream unzipped = new java.io.BufferedInputStream(
                        new java.util.zip.GZIPInputStream(input, 64 * 1024), 64 * 1024)) {
                    return EpgParser.parse(unzipped);
                }
            }
            return EpgParser.parse(input);
        }
    }

    private static String validUrl(URI epgUri) throws IOException {
        if (epgUri == null || epgUri.getScheme() == null) return null;
        String scheme = epgUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("La URL de programación no es HTTP.");
        }
        return epgUri.toString();
    }

    public static final class LoadResult {
        private final EpgData data;
        private final boolean changed;

        LoadResult(EpgData data, boolean changed) {
            this.data = data;
            this.changed = changed;
        }

        public EpgData getData() {
            return data;
        }

        public boolean isChanged() {
            return changed;
        }
    }
}
