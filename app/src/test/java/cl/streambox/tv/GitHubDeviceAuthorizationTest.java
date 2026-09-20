package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GitHubDeviceAuthorizationTest {
    @Test
    public void parsesDeviceCodeWithoutExposingItsPrivateValue() throws Exception {
        GitHubDeviceAuthorization.DeviceCode code =
                GitHubDeviceAuthorization.parseDeviceCode(new JSONObject()
                        .put("device_code", "private-device-code")
                        .put("user_code", "WDJB-MJHT")
                        .put("verification_uri", "https://github.com/login/device")
                        .put("expires_in", 900)
                        .put("interval", 5));

        assertEquals("WDJB-MJHT", code.getUserCode());
        assertEquals("https://github.com/login/device", code.getVerificationUri());
        assertEquals(5L, code.getIntervalSeconds());
        assertTrue(code.getExpiresAtMillis() > System.currentTimeMillis());
    }

    @Test
    public void parsesAccessAndRefreshTokensOnlyInMemory() throws Exception {
        GitHubDeviceAuthorization.TokenPair tokens =
                GitHubDeviceAuthorization.parseTokenResponse(new JSONObject()
                        .put("access_token", "gho_123456789012345678901234567890")
                        .put("refresh_token", "ghr_123456789012345678901234567890")
                        .put("expires_in", 28_800));

        assertEquals("gho_123456789012345678901234567890", tokens.getAccessToken());
        assertEquals("ghr_123456789012345678901234567890", tokens.getRefreshToken());
        assertTrue(tokens.getAccessExpiresAtMillis() > System.currentTimeMillis());
    }
}
