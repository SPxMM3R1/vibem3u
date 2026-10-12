package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ocultar y papelera desde la TV (0.5.90): pedido a GitHub y estado local. */
public final class ChannelTrashTest {
    @Test
    public void trashRequestCarriesOnlyTheEditorRowKeyAndAName() throws Exception {
        JSONObject request = GitHubChannelTrash.requestDocument(
                "provider:tvvoo:spain|vavoo_EUROSPORT%202%7Cgroup%3Aes", "EUROSPORT 2", "0.5.90", 0L);
        assertEquals(1, request.getInt("schema"));
        assertEquals("trash", request.getString("action"));
        assertEquals("provider:tvvoo:spain|vavoo_EUROSPORT%202%7Cgroup%3Aes", request.getString("rowKey"));
        assertEquals("EUROSPORT 2", request.getString("name"));
        assertEquals("1970-01-01T00:00:00Z", request.getString("requestedAt"));
        assertEquals(5, request.length());
    }

    @Test
    public void requestFilesAreSortableAndUnique() {
        String first = GitHubChannelTrash.requestFileName(1_760_000_000_000L, 0xabc);
        String later = GitHubChannelTrash.requestFileName(1_760_000_001_000L, 0x001);
        assertTrue(first.matches("\\d{8}T\\d{6}Z-[0-9a-f]{6}\\.json"));
        assertTrue(first.compareTo(later) < 0);
    }

    @Test
    public void namesAreCleanedBeforeLeavingTheTv() {
        assertEquals("TVN  HD", GitHubChannelTrash.safeName("TVN\n\tHD"));
        assertEquals(120, GitHubChannelTrash.safeName(String.join("", Collections.nCopies(200, "a"))).length());
    }

    @Test
    public void visibilityEntriesSurviveSerializationAndIgnoreGarbage() {
        String text = ChannelVisibility.serialize(Arrays.asList(
                new ChannelVisibility.Entry("m3u:0104", "TVN", ChannelVisibility.HIDDEN, 10L),
                new ChannelVisibility.Entry("provider:tvvoo:x", "EUROSPORT 2", ChannelVisibility.TRASHED, 20L)));
        Map<String, ChannelVisibility.Entry> parsed = ChannelVisibility.parse(text);
        assertEquals(2, parsed.size());
        assertEquals("TVN", parsed.get("m3u:0104").name);
        assertEquals(ChannelVisibility.TRASHED, parsed.get("provider:tvvoo:x").state);
        assertTrue(ChannelVisibility.parse("{roto").isEmpty());
        assertTrue(ChannelVisibility.parse("[{\"key\":\"m3u:x\",\"state\":\"borrar\"}]").isEmpty());
    }

    @Test
    public void githubTokensMustLookLikeTokens() {
        assertTrue(GitHubCredentialStore.isPlausibleToken("gho_0123456789abcdefABCDEF0123456789abcd"));
        assertFalse(GitHubCredentialStore.isPlausibleToken("corto"));
        assertFalse(GitHubCredentialStore.isPlausibleToken("gho_0123456789 abcdefABCDEF0123456789"));
    }
}
