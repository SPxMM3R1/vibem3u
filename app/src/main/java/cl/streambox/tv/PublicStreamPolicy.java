package cl.streambox.tv;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Rejects resolver candidates that can reach local, private or metadata networks. */
final class PublicStreamPolicy {
    private PublicStreamPolicy() {}

    static void requirePublicHttp(URI uri) throws IOException {
        if (uri == null || uri.getHost() == null
                || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("El resolutor publicó una URL no permitida.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost")
                || host.endsWith(".local")) {
            throw new IOException("El resolutor intentó acceder a una red local.");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException error) {
            throw new IOException("El host del stream no resolvió.", error);
        }
        if (addresses.length == 0) throw new IOException("El host del stream no resolvió.");
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IOException("El resolutor intentó acceder a una red no pública.");
            }
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress()
                || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isPublicIpv4(bytes, 0);
        if (bytes.length != 16) return false;
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        if ((first & 0xFE) == 0xFC            // fc00::/7 unique local
                || (first == 0xFE && (second & 0xC0) == 0x80) // fe80::/10
                || first == 0xFF) {           // multicast
            return false;
        }
        // IPv4-mapped IPv6 (::ffff:a.b.c.d).
        boolean mapped = true;
        for (int index = 0; index < 10; index++) mapped &= bytes[index] == 0;
        mapped &= (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
        return !mapped || isPublicIpv4(bytes, 12);
    }

    private static boolean isPublicIpv4(byte[] bytes, int offset) {
        int first = bytes[offset] & 0xFF;
        int second = bytes[offset + 1] & 0xFF;
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false; // CGNAT
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && (second == 168 || second == 0)) return false;
        return !(first == 198 && (second == 18 || second == 19));
    }
}
