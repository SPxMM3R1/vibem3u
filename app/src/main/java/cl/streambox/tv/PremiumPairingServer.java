package cl.streambox.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Servidor HTTP mínimo que vive solo mientras está abierto el diálogo de vinculación.
 * El teléfono (misma red) abre la página, pega el token y escribe el código de 4
 * dígitos que muestra la TV. El token no pasa por internet ni por terceros.
 *
 * <p>Límites: una conexión a la vez, cuerpo de 4 KB, 5 códigos errados y se cierra.
 * Al recibir un token válido responde y se apaga.</p>
 */
final class PremiumPairingServer {
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int MAX_BODY_BYTES = 4 * 1024;
    private static final int MAX_WRONG_CODES = 5;
    private static final int SOCKET_TIMEOUT_MS = 15_000;

    /** Resultado de entregar el token: se muestra tal cual en el teléfono. */
    static final class Outcome {
        final boolean accepted;
        final String message;

        Outcome(boolean accepted, String message) {
            this.accepted = accepted;
            this.message = message;
        }
    }

    interface Listener {
        /** Se llama en el hilo del servidor; puede verificar contra Highfly. */
        Outcome onToken(HighflyPremiumTokenRules.ParsedInput input);

        void onClosed(boolean linked, boolean tooManyAttempts);
    }

    private final String code;
    private final Listener listener;
    private volatile ServerSocket serverSocket;
    private volatile boolean stopped;
    private int wrongCodes;

    PremiumPairingServer(Listener listener) {
        this.listener = listener;
        this.code = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10_000));
    }

    String code() {
        return code;
    }

    /** Abre el puerto; devuelve el puerto asignado por el sistema. */
    int start() throws IOException {
        ServerSocket socket = new ServerSocket(0, 4);
        serverSocket = socket;
        Thread thread = new Thread(this::acceptLoop, "vibem3u-premium-pairing");
        thread.setDaemon(true);
        thread.start();
        return socket.getLocalPort();
    }

    void stop() {
        stopped = true;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Ya estaba cerrado.
            }
        }
    }

    /** IPv4 de la red activa (Wi-Fi o cable), o null si la TV no está en una red local. */
    static String localAddress(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return null;
        Network network = connectivity.getActiveNetwork();
        LinkProperties properties = network == null ? null : connectivity.getLinkProperties(network);
        if (properties == null) return null;
        for (LinkAddress address : properties.getLinkAddresses()) {
            InetAddress inet = address.getAddress();
            if (inet instanceof Inet4Address && !inet.isLoopbackAddress() && inet.isSiteLocalAddress()) {
                return inet.getHostAddress();
            }
        }
        return null;
    }

    private void acceptLoop() {
        boolean linked = false;
        boolean locked = false;
        while (!stopped) {
            ServerSocket socket = serverSocket;
            if (socket == null) break;
            try (Socket client = socket.accept()) {
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                int result = handle(client);
                if (result == 1) {
                    linked = true;
                    break;
                }
                if (result == -1) {
                    locked = true;
                    break;
                }
            } catch (IOException ignored) {
                // Socket cerrado por stop() o un cliente que se cortó: se sigue o se sale.
            }
        }
        stop();
        listener.onClosed(linked, locked);
    }

    /** 1 = vinculado, -1 = demasiados códigos errados, 0 = seguir esperando. */
    private int handle(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        String head = readHead(in);
        if (head == null) return 0;
        String requestLine = head.substring(0, Math.max(0, head.indexOf("\r\n")));
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";
        if ("GET".equals(method)) {
            if (path.equals("/") || path.startsWith("/?")) {
                respond(out, 200, PremiumPairingPage.form(null));
            } else {
                respond(out, 404, PremiumPairingPage.message("No encontrado.", false));
            }
            return 0;
        }
        if (!"POST".equals(method) || !path.equals("/pair")) {
            respond(out, 405, PremiumPairingPage.message("Método no permitido.", false));
            return 0;
        }
        int length = contentLength(head);
        if (length <= 0 || length > MAX_BODY_BYTES) {
            respond(out, 413, PremiumPairingPage.form("El contenido es demasiado largo."));
            return 0;
        }
        String body = new String(readExactly(in, length), StandardCharsets.UTF_8);
        String sentCode = formValue(body, "code");
        String sentToken = formValue(body, "token");
        if (!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                sentCode.trim().getBytes(StandardCharsets.UTF_8))) {
            wrongCodes++;
            if (wrongCodes >= MAX_WRONG_CODES) {
                respond(out, 403, PremiumPairingPage.message(
                        "Demasiados códigos incorrectos. Vuelve a abrir la vinculación en la TV.", false));
                return -1;
            }
            respond(out, 403, PremiumPairingPage.form("El código no coincide con el de la TV."));
            return 0;
        }
        HighflyPremiumTokenRules.ParsedInput input;
        try {
            input = HighflyPremiumTokenRules.parseInput(sentToken);
        } catch (IOException invalid) {
            respond(out, 400, PremiumPairingPage.form(
                    "Eso no parece un token ni un enlace de premium.highfly.to."));
            return 0;
        }
        Outcome outcome = listener.onToken(input);
        if (outcome.accepted) {
            respond(out, 200, PremiumPairingPage.message(outcome.message, true));
            return 1;
        }
        respond(out, 200, PremiumPairingPage.form(outcome.message));
        return 0;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int last = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            int value = in.read();
            if (value < 0) return null;
            buffer.write(value);
            // Los últimos cuatro bytes; las cabeceras terminan en \r\n\r\n.
            last = (last << 8) | (value & 0xFF);
            if (last == 0x0D0A0D0A) return buffer.toString(StandardCharsets.ISO_8859_1.name());
        }
        return null;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int count = in.read(data, read, length - read);
            if (count < 0) throw new IOException("Cuerpo incompleto.");
            read += count;
        }
        return data;
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    static String formValue(String body, String name) {
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (!key.equals(name)) continue;
            try {
                return URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1),
                        StandardCharsets.UTF_8.name());
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException invalid) {
                return "";
            }
        }
        return "";
    }

    private static void respond(OutputStream out, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 400 ? "Bad Request"
                : status == 403 ? "Forbidden" : status == 405 ? "Method Not Allowed" : "Payload Too Large";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
