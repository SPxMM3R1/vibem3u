package cl.streambox.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Playlist {
    private final List<Channel> channels;
    private final List<URI> epgUris;

    public Playlist(List<Channel> channels, URI epgUri) {
        this(
                channels,
                epgUri == null ? Collections.emptyList() : Collections.singletonList(epgUri),
                true
        );
    }

    public List<Channel> getChannels() { return channels; }

    /**
     * Returns every XMLTV URL declared by the M3U header. A list can expose
     * the EPG for channels from another source, so callers must not assume
     * that an EPG belongs only to the channels in this Playlist instance.
     */
    public List<URI> getEpgUris() { return epgUris; }

    /** Compatibility accessor for callers that only support one EPG URL. */
    public URI getEpgUri() {
        return epgUris.isEmpty() ? null : epgUris.get(0);
    }

    public static Playlist withEpgUris(List<Channel> channels, List<URI> epgUris) {
        return new Playlist(channels, epgUris, true);
    }

    private Playlist(List<Channel> channels, List<URI> epgUris, boolean multipleEpgUrls) {
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        List<URI> validUris = new ArrayList<>();
        if (epgUris != null) {
            for (URI epgUri : epgUris) {
                if (epgUri != null && !validUris.contains(epgUri)) {
                    validUris.add(epgUri);
                }
            }
        }
        this.epgUris = Collections.unmodifiableList(validUris);
    }
}
