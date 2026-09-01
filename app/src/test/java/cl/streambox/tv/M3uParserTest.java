package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.URI;
import java.util.List;

public class M3uParserTest {
    @Test
    public void parsesNamesLogosGroupsAndRelativeUrls() {
        String playlist = "#EXTM3U x-tvg-url=\"guide/epg.xml\"\n" +
                "#EXTINF:-1 tvg-id=\"norte.tv\" tvg-logo=\"logos/norte.png\" group-title=\"Deportes\",Norte Deportes HD\n" +
                "streams/norte.m3u8\n" +
                "#EXTINF:-1 tvg-name=\"Noticias 24\",\n" +
                "https://media.example.org/news.m3u8\n";

        Playlist parsed = M3uParser.parsePlaylist(playlist, URI.create("https://example.org/lists/tv.m3u"));
        List<Channel> channels = parsed.getChannels();

        assertEquals(2, channels.size());
        assertEquals("Norte Deportes HD", channels.get(0).getName());
        assertEquals("Deportes", channels.get(0).getGroup());
        assertEquals("https://example.org/lists/streams/norte.m3u8", channels.get(0).getStreamUri().toString());
        assertEquals("https://example.org/lists/logos/norte.png", channels.get(0).getLogoUri().toString());
        assertEquals("norte.tv", channels.get(0).getTvgId());
        assertEquals("Noticias 24", channels.get(1).getName());
        assertEquals("https://example.org/lists/guide/epg.xml", parsed.getEpgUri().toString());
    }

    @Test
    public void ignoresCommentsAndMalformedUrls() {
        String playlist = "#EXTM3U\n# comentario\n://mal\nhttps://example.org/ok.m3u8\n";
        List<Channel> channels = M3uParser.parse(playlist, URI.create("https://example.org/list.m3u"));
        assertEquals(1, channels.size());
        assertEquals("Canal 1", channels.get(0).getName());
        assertNull(channels.get(0).getLogoUri());
    }

    @Test
    public void keepsEveryEpgUrlDeclaredByTheM3uHeader() {
        String playlist = "#EXTM3U url-tvg=\"guide/first.xml,https://epg.example.org/second.xml\"\n"
                + "#EXTINF:-1 tvg-id=\"list-two-channel\",Canal de la lista 2\n"
                + "https://media.example.org/channel.m3u8\n";

        Playlist parsed = M3uParser.parsePlaylist(
                playlist,
                URI.create("https://example.org/lists/tv.m3u")
        );

        assertEquals(2, parsed.getEpgUris().size());
        assertEquals(
                "https://example.org/lists/guide/first.xml",
                parsed.getEpgUris().get(0).toString()
        );
        assertEquals(
                "https://epg.example.org/second.xml",
                parsed.getEpgUris().get(1).toString()
        );
    }

    @Test
    public void preservesDeclarativeResolverAttributes() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"SkySportsNFL.uk@TvVoo\" "
                + "x-resolver=\"tvvoo\" "
                + "x-resolver-endpoint=\"https://tvvoo.hayd.uk/stream/tv\" "
                + "x-resolver-ids=\"first;second\" "
                + "x-resolver-recipe=\"bounded-payload-v1\" "
                + "x-resolver-refresh=\"on_play\",Sky Sports NFL\n"
                + "https://example.org/fallback.m3u8\n";

        Channel channel = M3uParser.parse(
                playlist,
                URI.create("https://example.org/list.m3u")
        ).get(0);

        assertEquals("tvvoo", channel.getAttributes().get("x-resolver"));
        assertEquals(
                "https://tvvoo.hayd.uk/stream/tv",
                channel.getAttributes().get("x-resolver-endpoint")
        );
        assertEquals("first;second", channel.getAttributes().get("x-resolver-ids"));
        assertEquals(
                "bounded-payload-v1",
                channel.getAttributes().get("x-resolver-recipe")
        );
        assertEquals("on_play", channel.getAttributes().get("x-resolver-refresh"));
    }
}
