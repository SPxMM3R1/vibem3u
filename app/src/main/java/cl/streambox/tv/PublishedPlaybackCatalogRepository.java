package cl.streambox.tv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import android.content.Context;

/** Reads the public playback configuration; redirects stay pinned to raw GitHub. */
final class PublishedPlaybackCatalogRepository {
    static final String LAYOUT_URL =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/data/channel-editor-layout.json";
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Collections.singletonList("raw.githubusercontent.com"))
    );
    private final TokenHttpClient httpClient;

    PublishedPlaybackCatalogRepository(Context context) {
        this(context, new TokenHttpClient(6_000, 15_000));
    }

    PublishedPlaybackCatalogRepository(Context context, TokenHttpClient httpClient) {
        if (context == null) throw new IllegalArgumentException("context");
        this.httpClient = httpClient == null ? new TokenHttpClient(6_000, 15_000) : httpClient;
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
        return PublishedPlaybackCatalog.parse(document);
    }
}
