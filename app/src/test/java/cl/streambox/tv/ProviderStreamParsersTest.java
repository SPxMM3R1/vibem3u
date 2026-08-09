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
    public void parsesMeganoticiasConfigurationAndToken() throws Exception {
        ProviderStreamParsers.MeganoticiasConfig config =
                ProviderStreamParsers.parseMeganoticiasConfig(
                        "<script>var VideoSenalEnVivo = { id: 'dummy-mega', "
                                + "foo: 'ignored', serverKey: 'dummy-server-key' };</script>"
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
}
