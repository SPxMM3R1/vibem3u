package cl.streambox.tv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import android.content.Context;
import android.content.SharedPreferences;

/** Reads the public playback configuration; redirects stay pinned to raw GitHub. */
final class PublishedPlaybackCatalogRepository {
    static final String LAYOUT_URL =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/data/channel-editor-layout.json";
    private static final String PREFERENCES = "published_playback_catalog";
    private static final String CACHE_KEY = "document";
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Collections.singletonList("raw.githubusercontent.com"))
    );
    private final TokenHttpClient httpClient;
    private final SharedPreferences preferences;

    PublishedPlaybackCatalogRepository(Context context) {
        this(context, new TokenHttpClient(6_000, 15_000));
    }

    PublishedPlaybackCatalogRepository(Context context, TokenHttpClient httpClient) {
        if (context == null) throw new IllegalArgumentException("context");
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        this.httpClient = httpClient == null ? new TokenHttpClient(6_000, 15_000) : httpClient;
    }

    PublishedPlaybackCatalog loadCached() {
        String cached = preferences.getString(CACHE_KEY, "");
        if (cached == null || cached.trim().isEmpty()) return PublishedPlaybackCatalog.empty();
        try {
            return PublishedPlaybackCatalog.parse(cached);
        } catch (IOException ignored) {
            return PublishedPlaybackCatalog.empty();
        }
    }

    PublishedPlaybackCatalog refresh() throws IOException {
        Map<String, String> headers = Collections.singletonMap(
                "Accept", "application/json"
        );
        TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                LAYOUT_URL,
                headers,
                PublishedPlaybackCatalog.MAX_BYTES,
                null,
                ALLOWED_HOSTS
        );
        String document = new String(response.getBody(), StandardCharsets.UTF_8);
        PublishedPlaybackCatalog parsed = PublishedPlaybackCatalog.parse(document);
        preferences.edit().putString(CACHE_KEY, document).apply();
        return parsed;
    }
}
