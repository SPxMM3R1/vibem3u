package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void rejectsNumericPrefixMatchesSuchAsCanal1AgainstCanal10() {
        assertFalse(VavooSessionClient.strictChannelCompatible(
                "Canal1",
                "GB",
                "",
                "Canal10",
                "GB"
        ));
        assertTrue(VavooSessionClient.strictChannelCompatible(
                "Canal1",
                "GB",
                "",
                "Canal1 HD",
                "United Kingdom"
        ));
    }

    @Test
    public void rejectsCountryMismatchWhenChannelDeclaresCountry() {
        assertFalse(VavooSessionClient.strictChannelCompatible(
                "Sky Sports F1",
                "GB",
                "",
                "Sky Sports F1 HD",
                "Spain"
        ));
    }
}
