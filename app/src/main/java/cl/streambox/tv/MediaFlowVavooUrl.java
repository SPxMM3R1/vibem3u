package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Extracts the original Vavoo destination from a TvVoo proxy URL. */
final class MediaFlowVavooUrl {
    private static final String VAVOO_HOST = "vavoo.to";

    private MediaFlowVavooUrl() {}

    static URI fromTvVooProxy(URI proxy, URI expectedProxyOrigin) throws IOException {
        MediaFlowOriginPolicy.requireSameOrigin(proxy, expectedProxyOrigin);
        String path = proxy.getPath() == null ? "" : proxy.getPath();
        if (!"/extractor/video".equals(path)) {
            throw new IOException("TvVoo publicó una ruta MediaFlow no autorizada.");
        }
        String raw = queryValue(proxy.getRawQuery(), "d");
        if (AppStrings.isBlank(raw)) {
            throw new IOException("TvVoo no publicó el destino Vavoo.");
        }
        URI original;
        try {
            original = URI.create(URLDecoder.decode(raw, StandardCharsets.UTF_8.name()));
        } catch (Exception error) {
            throw new IOException("Destino Vavoo inválido.", error);
        }
        requireOriginal(original);
        return original;
    }

    static void requireOriginal(URI original) throws IOException {
        if (original == null
                || original.getHost() == null
                || original.getUserInfo() != null
                || original.getFragment() != null
                || !("http".equalsIgnoreCase(original.getScheme())
                || "https".equalsIgnoreCase(original.getScheme()))
                || !VAVOO_HOST.equalsIgnoreCase(original.getHost())) {
            throw new IOException("Destino Vavoo no autorizado.");
        }
        String path = original.getPath() == null ? "" : original.getPath();
        String normalized = path.toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("/vavoo-iptv/")
                || normalized.startsWith("/web-vod/"))) {
            throw new IOException("Ruta Vavoo no autorizada.");
        }
    }

    private static String queryValue(String query, String wanted) {
        if (query == null) return "";
        for (String item : query.split("&", -1)) {
            int equals = item.indexOf('=');
            String key = equals < 0 ? item : item.substring(0, equals);
            if (!wanted.equals(key)) continue;
            return equals < 0 ? "" : item.substring(equals + 1);
        }
        return "";
    }
}
