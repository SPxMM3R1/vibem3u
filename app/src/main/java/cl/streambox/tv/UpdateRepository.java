package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class UpdateRepository {
    private static final URI LATEST_RELEASE_URI =
            URI.create("https://api.github.com/repos/SPxMM3R1/vibem3u/releases/latest");
    private static final String RELEASE_PATH_PREFIX =
            "/SPxMM3R1/vibem3u/releases/download/";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_JSON_BYTES = 1_000_000;
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;

    interface ProgressListener {
        void onProgress(int percent);
    }

    UpdateInfo findAvailableUpdate(String currentVersionName) throws Exception {
        HttpURLConnection connection = openConnection(LATEST_RELEASE_URI);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("GitHub respondió " + status + ".");
        }

        String response;
        try (InputStream input = connection.getInputStream()) {
            response = readLimitedText(input, MAX_JSON_BYTES);
        } finally {
            connection.disconnect();
        }

        JSONObject release = new JSONObject(response);
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;

        String tagName = release.optString("tag_name", "").trim();
        String versionName = normalizeVersion(tagName);
        if (versionName.isEmpty() || !isNewerVersion(versionName, currentVersionName)) {
            return null;
        }

        String expectedAssetName = "VibeM3U-" + tagName + ".apk";
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("La versión no contiene un APK.");

        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null || !expectedAssetName.equals(asset.optString("name"))) continue;

            URI downloadUri = URI.create(asset.getString("browser_download_url"));
            validateReleaseUri(downloadUri);
            return new UpdateInfo(
                    tagName,
                    versionName,
                    downloadUri,
                    Math.max(0, asset.optLong("size", 0))
            );
        }
        throw new IOException("No se encontró " + expectedAssetName + ".");
    }

    File download(UpdateInfo update, File cacheDirectory, ProgressListener listener)
            throws Exception {
        validateReleaseUri(update.getDownloadUri());
        File updateDirectory = new File(cacheDirectory, "updates");
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de actualización.");
        }

        File partial = new File(updateDirectory, "VibeM3U-update.apk.part");
        File completed = new File(updateDirectory, "VibeM3U-update.apk");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("No se pudo limpiar la descarga anterior.");
        }

        HttpURLConnection connection = openFollowingRedirects(update.getDownloadUri());
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("La descarga respondió " + status + ".");
        }

        long contentLength = connection.getContentLength();
        if (contentLength <= 0) contentLength = update.getSizeBytes();
        if (contentLength > MAX_APK_BYTES) {
            connection.disconnect();
            throw new IOException("El APK supera el tamaño máximo permitido.");
        }
        long total = 0;
        int lastPercent = -1;

        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                total += read;
                if (total > MAX_APK_BYTES) {
                    throw new IOException("El APK supera el tamaño máximo permitido.");
                }
                if (contentLength > 0) {
                    int percent = (int) Math.min(100, (total * 100L) / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        listener.onProgress(percent);
                    }
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }

        if (total <= 0) {
            partial.delete();
            throw new IOException("GitHub entregó un archivo vacío.");
        }
        if (completed.exists() && !completed.delete()) {
            partial.delete();
            throw new IOException("No se pudo reemplazar la actualización anterior.");
        }
        if (!partial.renameTo(completed)) {
            partial.delete();
            throw new IOException("No se pudo finalizar la descarga.");
        }
        listener.onProgress(100);
        return completed;
    }

    static boolean isNewerVersion(String candidate, String current) {
        int[] candidateParts = numericParts(candidate);
        int[] currentParts = numericParts(current);
        int length = Math.max(candidateParts.length, currentParts.length);
        for (int index = 0; index < length; index++) {
            int candidatePart = index < candidateParts.length ? candidateParts[index] : 0;
            int currentPart = index < currentParts.length ? currentParts[index] : 0;
            if (candidatePart != currentPart) return candidatePart > currentPart;
        }
        return false;
    }

    private static int[] numericParts(String version) {
        String normalized = normalizeVersion(version);
        String stable = normalized.split("-", 2)[0];
        String[] pieces = stable.split("\\.");
        int[] result = new int[pieces.length];
        for (int index = 0; index < pieces.length; index++) {
            String digits = pieces[index].replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) return new int[]{0};
            try {
                result[index] = Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                return new int[]{0};
            }
        }
        return result;
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "";
        String normalized = version.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("v") ? normalized.substring(1) : normalized;
    }

    private static void validateReleaseUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getPath() == null
                || !uri.getPath().startsWith(RELEASE_PATH_PREFIX)
                || !uri.getPath().endsWith(".apk")) {
            throw new IOException("GitHub entregó una dirección de descarga no válida.");
        }
    }

    private static HttpURLConnection openFollowingRedirects(URI initialUri) throws Exception {
        URI current = initialUri;
        for (int redirect = 0; redirect < 6; redirect++) {
            HttpURLConnection connection = openConnection(current);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status < 300 || status >= 400) return connection;

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("GitHub redirigió la descarga sin destino.");
            }
            current = current.resolve(location);
            if (!"https".equalsIgnoreCase(current.getScheme())) {
                throw new IOException("GitHub intentó usar una descarga insegura.");
            }
        }
        throw new IOException("Demasiadas redirecciones al descargar.");
    }

    private static HttpURLConnection openConnection(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "VibeM3U-Android-TV");
        return connection;
    }

    private static String readLimitedText(InputStream input, int limit) throws IOException {
        StringBuilder result = new StringBuilder();
        int total = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IOException("Respuesta de GitHub demasiado grande.");
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }
}
