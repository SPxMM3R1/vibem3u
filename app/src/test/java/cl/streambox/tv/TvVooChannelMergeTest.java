package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TvVooChannelMergeTest {
    @Test
    public void m3uOrderAndPresentationWinAndCatalogLogoFillsGap() {
        Channel fromM3u = channel(
                "Lista name",
                "stable-uk",
                "vavoo_SKY%201|group:uk",
                "uk",
                null
        );
        Channel selected = channel(
                "Catalog name",
                "other-id",
                "vavoo_SKY 1|group:uk",
                "United Kingdom",
                URI.create("https://logos.example/sky.png")
        );
        List<Channel> merged = TvVooChannelMerge.merge(
                Collections.singletonList(fromM3u),
                Collections.singletonList(selected),
                true
        );

        assertEquals(1, merged.size());
        assertEquals("Lista name", merged.get(0).getName());
        assertEquals(fromM3u.getStreamUri(), merged.get(0).getStreamUri());
        assertEquals("https://logos.example/sky.png", merged.get(0).getLogoUri().toString());
    }

    @Test
    public void sameDisplayNameDifferentAliasIsKeptAndSelectionIsAppended() {
        Channel first = channel(
                "News", "first", "vavoo_NEWS%201|group:uk", "uk", null
        );
        Channel selected = channel(
                "News", "second", "vavoo_NEWS%202|group:uk", "uk", null
        );

        List<Channel> merged = TvVooChannelMerge.merge(
                Collections.singletonList(first),
                Collections.singletonList(selected),
                true
        );

        assertEquals(2, merged.size());
        assertEquals("News", merged.get(0).getName());
        assertEquals("News", merged.get(1).getName());
        assertEquals("second", merged.get(1).getTvgId());
    }

    @Test
    public void disabledSelectionDoesNotAddCachedRows() {
        Channel selected = channel(
                "Only selected", "selected", "vavoo_ONLY|group:cl", "cl", null
        );
        assertEquals(
                0,
                TvVooChannelMerge.merge(
                        Collections.emptyList(),
                        Collections.singletonList(selected),
                        false
                ).size()
        );
    }

    @Test
    public void duplicateExactAliasAndCountryKeepsFirstM3uRow() {
        Channel first = channel(
                "First", "one", "vavoo_DUP|group:uk", "uk", null
        );
        Channel second = channel(
                "Second", "two", "vavoo_DUP%7Cgroup%3Auk", "uk", null
        );
        List<Channel> merged = TvVooChannelMerge.merge(
                Arrays.asList(first, second),
                Collections.singletonList(first),
                true
        );
        assertEquals(1, merged.size());
        assertEquals("First", merged.get(0).getName());
    }

    private static Channel channel(
            String name,
            String tvgId,
            String alias,
            String country,
            URI logo
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", tvgId);
        attributes.put("tvg-country", country);
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-ids", alias);
        return new Channel(
                name,
                URI.create("tvvoo://channel/" + tvgId),
                logo,
                "TvVoo",
                attributes
        );
    }
}
