package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** «Conservar» desde la TV (0.6.52): pedido a GitHub y lista publicada. */
public final class ChannelKeepTest {
    @Test
    public void keepRequestsUseTheSameDocumentAsTheTrash() throws Exception {
        JSONObject keep = GitHubChannelTrash.requestDocument(
                GitHubChannelTrash.ACTION_KEEP, "m3u:IPTVOrg.CentralTV.cl-SD", "Central TV", "0.6.52", 0L);
        assertEquals("keep", keep.getString("action"));
        assertEquals("m3u:IPTVOrg.CentralTV.cl-SD", keep.getString("rowKey"));
        assertEquals(6, keep.length());
        JSONObject unkeep = GitHubChannelTrash.requestDocument(
                GitHubChannelTrash.ACTION_UNKEEP, "m3u:IPTVOrg.CentralTV.cl-SD", "Central TV", "0.6.52", 0L);
        assertEquals("unkeep", unkeep.getString("action"));
        assertEquals("Conservar desde la app: ", GitHubChannelTrash.commitMessage(GitHubChannelTrash.ACTION_KEEP));
        assertEquals("Papelera desde la app: ", GitHubChannelTrash.commitMessage(GitHubChannelTrash.ACTION_TRASH));
    }

    @Test
    public void publishedListKeepsOnlyPlausibleKeys() {
        assertEquals(Arrays.asList("m3u:a", "provider:tvvoo:b"), new ArrayList<>(ChannelKeep.parseRemote(
                "{\"schema\":1,\"rowKeys\":[\"m3u:a\",\"\",\"provider:tvvoo:b\",\"m3u:a\"]}")));
        assertTrue(ChannelKeep.parseRemote("{no es json").isEmpty());
        assertTrue(ChannelKeep.parseRemote("").isEmpty());
    }
}
