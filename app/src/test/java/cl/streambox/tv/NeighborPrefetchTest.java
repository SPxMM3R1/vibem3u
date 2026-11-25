package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vecinos que se precalientan (0.6.74): solo directos, sin repetir ni el canal que suena. */
public class NeighborPrefetchTest {
    @Test
    public void returnsNextThenPreviousAmongDirectNeighbors() {
        Channel directA = channel("A");
        Channel vavooB = channel("B");
        Channel directC = channel("C");

        List<Channel> neighbors = NeighborPrefetch.directNeighbors(
                Arrays.asList(directA, vavooB, directC), 1, vavooB,
                channel -> channel == vavooB);

        assertEquals(Arrays.asList(directC, directA), neighbors);
    }

    @Test
    public void emptyWhenBothNeighborsNeedAresolver() {
        Channel directA = channel("A");
        Channel vavooB = channel("B");
        Channel vavooC = channel("C");

        List<Channel> neighbors = NeighborPrefetch.directNeighbors(
                Arrays.asList(directA, vavooB, vavooC), 0, directA,
                channel -> channel != directA);

        assertTrue(neighbors.isEmpty());
    }

    @Test
    public void twoChannelListReturnsTheSameNeighborOnce() {
        Channel a = channel("A");
        Channel b = channel("B");

        List<Channel> neighbors = NeighborPrefetch.directNeighbors(
                Arrays.asList(a, b), 0, a, channel -> false);

        assertEquals(Collections.singletonList(b), neighbors);
    }

    @Test
    public void oneOrEmptyListHasNoNeighbors() {
        Channel a = channel("A");

        assertTrue(NeighborPrefetch.directNeighbors(
                Collections.singletonList(a), 0, a, channel -> false).isEmpty());
        assertTrue(NeighborPrefetch.directNeighbors(
                Collections.emptyList(), 0, null, channel -> false).isEmpty());
    }

    @Test
    public void wrapsAroundAtBothEdges() {
        Channel a = channel("A");
        Channel b = channel("B");
        Channel c = channel("C");
        List<Channel> channels = Arrays.asList(a, b, c);

        assertEquals(Arrays.asList(b, c), NeighborPrefetch.directNeighbors(
                channels, 0, a, channel -> false));
        assertEquals(Arrays.asList(a, b), NeighborPrefetch.directNeighbors(
                channels, 2, c, channel -> false));
    }

    private static Channel channel(String name) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "test-" + name);
        return new Channel(name, URI.create("http://127.0.0.1/" + name), null, "Test", attributes);
    }
}
