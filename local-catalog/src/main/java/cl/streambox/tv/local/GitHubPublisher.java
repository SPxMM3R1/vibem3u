package cl.streambox.tv.local;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Publishes the editor's three declared JSON documents with the local gh credential. */
final class GitHubPublisher {
    static final String REPOSITORY = "SPxMM3R1/lista-m3u";
    static final String BRANCH = "main";
    private static final Set<String> ALLOWED_FILES = Set.of(
            "data/channel-editor-layout.json",
            "data/vibem3u-selection.json",
            "presentation-overrides.json"
    );
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    boolean isAuthenticated() {
        try {
            runGh("auth", "status", "--hostname", "github.com");
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    void startAuthentication() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new IOException("La autorización asistida está disponible en Windows.");
        }
        new ProcessBuilder(
                "cmd.exe", "/c", "start", "", "gh", "auth", "login",
                "--web", "--hostname", "github.com"
        ).start();
    }

    PublishResult publish(JSONObject request) throws IOException {
        JSONObject documents = request.optJSONObject("documents");
        JSONObject expectedShas = request.optJSONObject("expectedShas");
        if (documents == null || expectedShas == null) {
            throw new IOException("Faltan los documentos o las versiones base del catálogo.");
        }
        if (documents.length() != ALLOWED_FILES.size()) {
            throw new IOException("El editor debe enviar exactamente los tres documentos autorizados.");
        }

        int totalBytes = 0;
        Map<String, String> contentByPath = new LinkedHashMap<>();
        for (String path : ALLOWED_FILES) {
            Object value = documents.opt(path);
            if (!(value instanceof String)) throw new IOException("Documento de catálogo inválido.");
            String content = (String) value;
            totalBytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_DOCUMENT_BYTES) throw new IOException("El catálogo supera el límite de tamaño.");
            try {
                new JSONObject(content);
            } catch (RuntimeException invalid) {
                throw new IOException("Uno de los documentos no contiene JSON válido.");
            }
            contentByPath.put(path, content);
        }
        for (String key : documents.keySet()) {
            if (!ALLOWED_FILES.contains(key)) throw new IOException("El editor intentó publicar un archivo no permitido.");
        }

        JSONObject ref = request("GET", "git/ref/heads/" + BRANCH, null);
        String headSha = ref.getJSONObject("object").optString("sha", "");
        if (headSha.isBlank()) throw new IOException("No se pudo leer la rama main de GitHub.");

        for (String path : ALLOWED_FILES) {
            JSONObject remote = contents(path);
            String actualSha = remote == null ? null : remote.optString("sha", null);
            String expectedSha = expectedShas.isNull(path) ? null : expectedShas.optString(path, null);
            if (!equal(expectedSha, actualSha)) {
                throw new ConflictException("El catálogo cambió en GitHub mientras lo estabas editando. Recarga la página antes de publicar.");
            }
        }

        JSONObject baseCommit = request("GET", "git/commits/" + headSha, null);
        JSONArray treeEntries = new JSONArray();
        for (Map.Entry<String, String> document : contentByPath.entrySet()) {
            treeEntries.put(new JSONObject()
                    .put("path", document.getKey())
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("content", document.getValue()));
        }
        JSONObject tree = request("POST", "git/trees", new JSONObject()
                .put("base_tree", baseCommit.getJSONObject("tree").getString("sha"))
                .put("tree", treeEntries));
        JSONObject commit = request("POST", "git/commits", new JSONObject()
                .put("message", "Actualiza selección y orden del catálogo VibeM3U")
                .put("tree", tree.getString("sha"))
                .put("parents", new JSONArray().put(headSha)));
        String commitSha = commit.getString("sha");
        request("PATCH", "git/refs/heads/" + BRANCH, new JSONObject()
                .put("sha", commitSha)
                .put("force", false));

        Map<String, String> blobShas = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : contentByPath.entrySet()) {
            blobShas.put(entry.getKey(), gitBlobSha(entry.getValue()));
        }
        return new PublishResult(commitSha, blobShas);
    }

    private JSONObject contents(String path) throws IOException {
        try {
            return request("GET", "contents/" + path + "?ref=" + BRANCH, null);
        } catch (GhApiException error) {
            if (error.output.contains("HTTP 404") || error.output.contains("Not Found")) return null;
            throw error;
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws IOException {
        String endpoint = "repos/" + REPOSITORY + "/" + path;
        String output = body == null
                ? runGh("api", "--hostname", "github.com", "--method", method, endpoint)
                : runGhWithInput(body.toString(), "api", "--hostname", "github.com", "--method", method, "--input", "-", endpoint);
        try {
            return new JSONObject(output);
        } catch (RuntimeException invalid) {
            throw new IOException("GitHub devolvió una respuesta inesperada. No se publicó el catálogo.");
        }
    }

    private static String runGh(String... arguments) throws IOException {
        return runGhWithInput(null, arguments);
    }

    private static String runGhWithInput(String input, String... arguments) throws IOException {
        String[] command = new String[arguments.length + 1];
        command[0] = "gh";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException missingCli) {
            throw new IOException("No se encontró GitHub CLI (gh). Instálalo y autoriza tu cuenta para publicar.");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), output), "catalog-gh-output");
        reader.setDaemon(true);
        reader.start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null) stdin.write(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A failed API process can close stdin before the request is written.
        }
        try {
            if (!process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("GitHub tardó demasiado en responder.");
            }
            reader.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Publicación cancelada.");
        }
        String text = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            if (text.contains("HTTP 401") || text.contains("HTTP 403")) {
                throw new GhApiException("GitHub rechazó la publicación. Comprueba que la cuenta conectada tenga acceso de escritura al repositorio.", text);
            }
            throw new GhApiException("No se pudo completar la publicación en GitHub.", text);
        }
        return text;
    }

    private static void copy(InputStream input, OutputStream output) {
        try (input; output) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > 2 * 1024 * 1024) throw new IOException("Respuesta demasiado grande.");
                output.write(buffer, 0, count);
            }
        } catch (IOException ignored) {
            // The caller reports the GitHub command's exit status.
        }
    }

    private static String gitBlobSha(String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            byte[] digest = sha1.digest(bytes);
            StringBuilder result = new StringBuilder(40);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-1 no está disponible en Java.");
        }
    }

    private static boolean equal(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        copy(input, (OutputStream) output);
    }

    static final class PublishResult {
        final String commitSha;
        final Map<String, String> blobShas;

        PublishResult(String commitSha, Map<String, String> blobShas) {
            this.commitSha = commitSha;
            this.blobShas = blobShas;
        }
    }

    static final class ConflictException extends IOException {
        ConflictException(String message) { super(message); }
    }

    private static final class GhApiException extends IOException {
        final String output;
        GhApiException(String message, String output) {
            super(message);
            this.output = output == null ? "" : output;
        }
    }
}
