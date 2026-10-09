package cl.streambox.tv.local;

import cl.streambox.tv.NautaStreamResolver;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Live, sanitized Nauta channel discovery for the loopback catalogue editor. */
final class NautaCatalogSource {
    private static final String BASE_URL = NautaStreamResolver.BASE;
    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final int MAX_CATALOG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CATEGORIES = 100;
    private static final int MAX_CHANNELS = 2_000;
    private static final long CATEGORY_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final Pattern CATEGORY_ID = Pattern.compile("cat_[0-9]+|nautatv_catalog");
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f]");

    private final OkHttpClient http;
    private final HttpUrl baseUrl;
    private volatile CategorySnapshot categorySnapshot;

    NautaCatalogSource() {
        this(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(), BASE_URL);
    }

    NautaCatalogSource(OkHttpClient http, String baseUrl) {
        if (http == null) throw new IllegalArgumentException("Falta el cliente HTTP local de Nauta.");
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || parsed.query() != null || parsed.fragment() != null
                || !parsed.encodedPath().endsWith("/")) {
            throw new IllegalArgumentException("La dirección local del catálogo Nauta no es válida.");
        }
        this.http = http;
        this.baseUrl = parsed;
    }

    JSONObject categories() throws IOException {
        JSONArray categories = new JSONArray();
        for (Category category : loadCategories()) {
            categories.put(new JSONObject().put("id", category.id).put("name", category.name));
        }
        return new JSONObject().put("categories", categories);
    }

    JSONObject catalog(String categoryId) throws IOException {
        Category category = categoryById(categoryId);
        if (category == null) throw new IllegalArgumentException("La categoría Nauta ya no está disponible. Actualiza la fuente.");
        JSONObject document = getJson(MAX_CATALOG_BYTES, "catalog", "tv", category.id + ".json");
        JSONArray metas = document.optJSONArray("metas");
        if (metas == null || metas.length() > MAX_CHANNELS) {
            throw new IOException("Nauta devolvió un catálogo con un formato no reconocido.");
        }

        JSONArray channels = new JSONArray();
        Set<String> seenNames = new LinkedHashSet<>();
        for (int index = 0; index < metas.length(); index++) {
            JSONObject meta = metas.optJSONObject(index);
            if (meta == null || !"tv".equals(meta.optString("type", "tv"))) continue;
            String exactName = meta.optString("name", "");
            if (!validLocatorName(exactName) || !seenNames.add(exactName)) continue;
            String tvgId = stableChannelId(exactName);
            channels.put(new JSONObject()
                    .put("tvgId", tvgId)
                    .put("name", exactName)
                    .put("categoryId", category.id)
                    .put("categoryName", category.name));
        }
        return new JSONObject()
                .put("category", new JSONObject().put("id", category.id).put("name", category.name))
                .put("channels", channels);
    }

    private Category categoryById(String id) throws IOException {
        if (id == null || !CATEGORY_ID.matcher(id).matches()) return null;
        for (Category category : loadCategories()) if (category.id.equals(id)) return category;
        return null;
    }

    private List<Category> loadCategories() throws IOException {
        CategorySnapshot snapshot = categorySnapshot;
        long now = System.currentTimeMillis();
        if (snapshot != null && now - snapshot.fetchedAt < CATEGORY_TTL_MS) return snapshot.categories;
        synchronized (this) {
            snapshot = categorySnapshot;
            now = System.currentTimeMillis();
            if (snapshot != null && now - snapshot.fetchedAt < CATEGORY_TTL_MS) return snapshot.categories;
            JSONObject manifest = getJson(MAX_MANIFEST_BYTES, "manifest.json");
            JSONArray raw = manifest.optJSONArray("catalogs");
            if (raw == null || raw.length() > MAX_CATEGORIES) {
                throw new IOException("Nauta devolvió un manifiesto con un formato no reconocido.");
            }
            List<Category> categories = new ArrayList<>();
            Set<String> seenIds = new LinkedHashSet<>();
            for (int index = 0; index < raw.length(); index++) {
                JSONObject item = raw.optJSONObject(index);
                if (item == null || !"tv".equals(item.optString("type", "tv"))) continue;
                String id = item.optString("id", "");
                String name = cleanText(item.optString("name", ""), 120);
                if (!CATEGORY_ID.matcher(id).matches() || name.isEmpty() || !seenIds.add(id)) continue;
                categories.add(new Category(id, name));
            }
            if (categories.isEmpty()) throw new IOException("El manifiesto Nauta no publicó categorías de TV compatibles.");
            snapshot = new CategorySnapshot(now, List.copyOf(categories));
            categorySnapshot = snapshot;
            return snapshot.categories;
        }
    }

    private JSONObject getJson(int maximumBytes, String... pathSegments) throws IOException {
        HttpUrl.Builder url = baseUrl.newBuilder();
        for (String segment : pathSegments) url.addPathSegment(segment);
        Request request = new Request.Builder()
                .url(url.build())
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.request().url().host().equalsIgnoreCase(baseUrl.host()) || !response.isSuccessful()) {
                throw new IOException("Nauta no está disponible ahora. HTTP " + response.code() + ".");
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Nauta devolvió una respuesta vacía.");
            long declaredLength = body.contentLength();
            if (declaredLength > maximumBytes) throw new IOException("El catálogo Nauta supera el tamaño permitido.");
            byte[] bytes = body.byteStream().readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) throw new IOException("El catálogo Nauta supera el tamaño permitido.");
            try {
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException malformed) {
                throw new IOException("Nauta devolvió JSON no válido.");
            }
        } catch (IOException unavailable) {
            if (unavailable.getMessage() != null && unavailable.getMessage().startsWith("Nauta ")) throw unavailable;
            throw new IOException("No se pudo consultar Nauta. Revisa la conexión e inténtalo otra vez.");
        }
    }

    private static boolean validLocatorName(String exactName) {
        return exactName != null && !exactName.isBlank() && exactName.length() <= 200
                && !CONTROL.matcher(exactName).find() && !exactName.contains("\\")
                && !exactName.contains("?") && !exactName.contains("#") && !exactName.contains("://");
    }

    private static String cleanText(String value, int maximum) {
        if (value == null || CONTROL.matcher(value).find()) return "";
        String cleaned = value.trim();
        return cleaned.length() <= maximum ? cleaned : "";
    }

    private static String stableChannelId(String exactName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(exactName.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return "Nauta." + hex.substring(0, 24) + "@Nauta";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
        }
    }

    private static final class Category {
        final String id;
        final String name;
        Category(String id, String name) { this.id = id; this.name = name; }
    }

    private static final class CategorySnapshot {
        final long fetchedAt;
        final List<Category> categories;
        CategorySnapshot(long fetchedAt, List<Category> categories) {
            this.fetchedAt = fetchedAt;
            this.categories = categories;
        }
    }
}
