package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class VavooSessionClientTest {
    @Test
    public void parsesEncodedTvVooAliasIntoStableTarget() {
        VavooSessionClient.Target target = VavooSessionClient.targetFromAlias(
                "vavoo_SKY%20SPORTS%20F1%20HD%7Cgroup%3Auk"
        );

        assertNotNull(target);
        assertEquals("skysportsf1hd", target.exactName);
        assertEquals("skysportsf1", target.relaxedName);
        assertEquals("unitedkingdom", target.country);
    }

    @Test
    public void mapsCountryAliasesWithoutChangingChannelName() {
        VavooSessionClient.Target target = VavooSessionClient.targetFromAlias(
                "vavoo_EUROSPORT%202%7Cgroup%3Aes"
        );

        assertNotNull(target);
        assertEquals("eurosport2", target.exactName);
        assertEquals("spain", target.country);
    }

    @Test
    public void rejectsEmptyAlias() {
        assertNull(VavooSessionClient.targetFromAlias(""));
    }

    @Test
    public void keepsReadableSearchTextForTargetedCatalogQueries() {
        VavooSessionClient.Target target = VavooSessionClient.targetFromAlias(
                "vavoo_SKY%20SPORTS%20RACING%20HD%7Cgroup%3Auk"
        );

        assertNotNull(target);
        assertEquals("SKY SPORTS RACING HD", target.searchName);
        assertEquals("unitedkingdom", target.country);
    }
}
