package cl.streambox.tv.local;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
    /** Corrección manual de la guía de un canal (Lista M3U, guía por canal desde 2026-10-12). */
    static final Pattern EPG_MANUAL_PATH = Pattern.compile("^epg/manual/[A-Za-z0-9._@%~-]{1,200}\\.xml$");
    private static final int MAX_EPG_MANUAL_BYTES = 256 * 1024;
    private static final int MAX_PUBLISH_ATTEMPTS = 6;

    @FunctionalInterface
    interface ApiRequest {
        String execute(String method, String path, JSONObject body) throws IOException;
    }

    private final ApiRequest apiRequest;

    GitHubPublisher() {
        this(GitHubPublisher::requestThroughGh);
    }

    GitHubPublisher(ApiRequest apiRequest) {
        this.apiRequest = apiRequest;
    }

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
        if (documents == null) throw new IOException("Faltan los documentos del catálogo.");
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

        return new PublishResult(commitFiles(contentByPath,
                "Actualiza catálogo editorial desde el editor local"));
    }

    /**
     * Publica la corrección manual de la guía de un solo canal. Solo acepta una ruta
     * {@code epg/manual/<canal>.xml} con un XMLTV {@code <tv>}; nada más del repositorio.
     */
    PublishResult publishEpgManual(JSONObject request) throws IOException {
        String path = request.optString("path", "");
        Object value = request.opt("content");
        if (!EPG_MANUAL_PATH.matcher(path).matches() || path.contains("..")) {
            throw new IOException("Ruta de corrección de guía no permitida.");
        }
        if (!(value instanceof String)) throw new IOException("Falta el contenido de la corrección.");
        String content = (String) value;
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_EPG_MANUAL_BYTES) {
            throw new IOException("La corrección de guía supera el límite de tamaño.");
        }
        String trimmed = content.strip();
        if (!(trimmed.startsWith("<?xml") || trimmed.startsWith("<tv")) || !trimmed.contains("<tv")
                || !(trimmed.endsWith("</tv>") || trimmed.endsWith("/>"))) {
            throw new IOException("La corrección de guía debe ser un XMLTV con raíz <tv>.");
        }
        String channel = request.optString("channel", "").replaceAll("[\r\n]", " ").strip();
        if (channel.length() > 120) channel = channel.substring(0, 120);
        Map<String, String> contentByPath = new LinkedHashMap<>();
        contentByPath.put(path, content);
        return new PublishResult(commitFiles(contentByPath,
                "Guía manual desde el editor local" + (channel.isEmpty() ? "" : ": " + channel)));
    }

    private String commitFiles(Map<String, String> contentByPath, String message) throws IOException {
        String commitSha = null;
        for (int attempt = 1; attempt <= MAX_PUBLISH_ATTEMPTS; attempt++) {
            JSONObject ref = request("GET", "git/ref/heads/" + BRANCH, null);
            String headSha = ref.getJSONObject("object").optString("sha", "");
            if (headSha.isBlank()) throw new IOException("No se pudo leer la rama main de GitHub.");

            // Rebuild the editorial commit on the newest remote tree each time.
            // Runner-owned files remain untouched; the editor's three declared
            // documents intentionally take precedence over their remote copies.
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
                    .put("message", message)
                    .put("tree", tree.getString("sha"))
                    .put("parents", new JSONArray().put(headSha)));
            String candidateSha = commit.getString("sha");
            try {
                request("PATCH", "git/refs/heads/" + BRANCH, new JSONObject()
                        .put("sha", candidateSha)
                        .put("force", false));
                commitSha = candidateSha;
                break;
            } catch (IOException concurrentUpdate) {
                if (!isBranchRace(concurrentUpdate)) throw concurrentUpdate;
                if (attempt == MAX_PUBLISH_ATTEMPTS) {
                    throw new ConflictException("GitHub siguió recibiendo cambios durante la publicación. Tu edición local no se perdió; vuelve a pulsar Publicar.");
                }
                pauseBeforeRetry(attempt);
            }
        }
        if (commitSha == null) throw new IOException("No se pudo publicar el catálogo.");
        return commitSha;

    }

    private JSONObject request(String method, String path, JSONObject body) throws IOException {
        String output = apiRequest.execute(method, path, body);
        try {
            return new JSONObject(output);
        } catch (RuntimeException invalid) {
            throw new IOException("GitHub devolvió una respuesta inesperada. No se publicó el catálogo.");
        }
    }

    private static String requestThroughGh(String method, String path, JSONObject body) throws IOException {
        String endpoint = "repos/" + REPOSITORY + "/" + path;
        String output = body == null
                ? runGh("api", "--hostname", "github.com", "--method", method, endpoint)
                : runGhWithInput(body.toString(), "api", "--hostname", "github.com", "--method", method, "--input", "-", endpoint);
        return output;
    }

    private static boolean isBranchRace(IOException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (error instanceof GhApiException) message += " " + ((GhApiException) error).output;
        String text = message.toLowerCase(java.util.Locale.ROOT);
        return (text.contains("409") || text.contains("422"))
                && (text.contains("fast forward") || text.contains("reference") || text.contains("ref update"));
    }

    private static void pauseBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(Math.min(100L * attempt, 500L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Publicación cancelada durante la integración de un cambio remoto.");
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

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        copy(input, (OutputStream) output);
    }

    static final class PublishResult {
        final String commitSha;

        PublishResult(String commitSha) {
            this.commitSha = commitSha;
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
