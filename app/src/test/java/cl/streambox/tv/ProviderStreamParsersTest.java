package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ProviderStreamParsersTest {
    @Test
    public void parsesTvnConfigurationWithoutPersistingAnything() throws Exception {
        ProviderStreamParsers.TvnConfig config = ProviderStreamParsers.parseTvn(
                "<script>const player = { id: 'dummy-stream-01', "
                        + "access_token: 'dummy.token-~' };</script>"
        );

        assertEquals("dummy-stream-01", config.getStreamId());
        assertEquals("dummy.token-~", config.getAccessToken());
    }

    @Test
    public void findsTvnLivePlayerAddressPublishedOnTvnCl() {
        assertEquals(
                "https://tvn-live-test-1.southamerica-west1.run.app",
                ProviderStreamParsers.parseTvnLivePageUrl(
                        "<div data-media-cntr=\"ms-player\" "
                                + "data-urlenvivo=\"https://tvn-live-test-1.southamerica-west1.run.app\"></div>"));
        assertEquals(
                "https://live.tvn.cl/?a=1&b=2",
                ProviderStreamParsers.parseTvnLivePageUrl(
                        "<div data-tvnplayer-urlenvivo=\"https://live.tvn.cl/?a=1&amp;b=2\"></div>"));
    }

    @Test
    public void ignoresUntrustedTvnLivePlayerAddresses() {
        org.junit.Assert.assertNull(ProviderStreamParsers.parseTvnLivePageUrl(
                "<div data-urlenvivo=\"https://evil.example/tvn\"></div>"));
        org.junit.Assert.assertNull(ProviderStreamParsers.parseTvnLivePageUrl(
                "<div data-urlenvivo=\"https://other.run.app\"></div>"));
        org.junit.Assert.assertNull(ProviderStreamParsers.parseTvnLivePageUrl("<div></div>"));
    }

    @Test
    public void parsesMeganoticiasConfigurationAndToken() throws Exception {
        ProviderStreamParsers.MeganoticiasConfig config =
                ProviderStreamParsers.parseMeganoticiasConfig(
                        "<script>var VideoSenalEnVivo = { id: 'dummy-mega', "
                                + "foo: 'ignored }', serverKey: 'dummy-server-key' };</script>"
                );

        assertEquals("dummy-mega", config.getStreamId());
        assertEquals("dummy-server-key", config.getServerKey());
        assertEquals(
                "dummy.access-token-1",
                ProviderStreamParsers.parseMeganoticiasAccessToken(
                        "{\"access_token\":\"dummy.access-token-1\"}"
                )
        );
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsMissingTvnToken() throws Exception {
        ProviderStreamParsers.parseTvn("<script>const player = { id: 'dummy' };</script>");
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsUnexpectedMeganoticiasTokenCharacters() throws Exception {
        ProviderStreamParsers.parseMeganoticiasAccessToken(
                "{\"access_token\":\"dummy token with spaces\"}"
        );
    }

    @Test
    public void supportsCatalogDrivenMegaPatternsAndJsonPath() throws Exception {
        ProviderStreamParsers.MeganoticiasConfig config =
                ProviderStreamParsers.parseMeganoticiasConfig(
                        "window.signal = { stream: 'changed-stream', secret: 'changed-key' };",
                        "stream\\s*:\\s*'([^']+)'",
                        "secret\\s*:\\s*'([^']+)'"
                );

        assertEquals("changed-stream", config.getStreamId());
        assertEquals("changed-key", config.getServerKey());
        assertEquals(
                "fresh.token-2",
                ProviderStreamParsers.parseMeganoticiasAccessToken(
                        "{\"data\":{\"authorization\":\"fresh.token-2\"}}",
                        "data.authorization"
                )
        );
    }
}
