package cl.streambox.tv;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One validated playlist retained briefly for the current Media3 handoff. */
public final class ManifestHandoffData {
    private final URI originalUri;
    private final URI finalUri;
    private final Map<String, String> headers;
    private final byte[] rawBytes;
    private final long capturedAtMillis;

    ManifestHandoffData(
            URI originalUri,
            URI finalUri,
            Map<String, String> headers,
            byte[] rawBytes,
            long capturedAtMillis
    ) {
        this.originalUri = originalUri;
        this.finalUri = finalUri == null ? originalUri : finalUri;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                headers == null ? Collections.emptyMap() : headers
        ));
        this.rawBytes = rawBytes == null ? new byte[0] : Arrays.copyOf(rawBytes, rawBytes.length);
        this.capturedAtMillis = capturedAtMillis;
    }

    public URI getOriginalUri() {
        return originalUri;
    }

    public URI getFinalUri() {
        return finalUri;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public byte[] getRawBytes() {
        return Arrays.copyOf(rawBytes, rawBytes.length);
    }

    /** Alias for code that describes the bytes as a body. */
    public byte[] getBody() {
        return getRawBytes();
    }

    public long getCapturedAtMillis() {
        return capturedAtMillis;
    }

    void clearBytes() {
        Arrays.fill(rawBytes, (byte) 0);
    }
}
