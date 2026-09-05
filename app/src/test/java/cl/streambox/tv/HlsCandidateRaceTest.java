package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HlsCandidateRaceTest {
    @Test(timeout = 3_000L)
    public void returnsTheFirstPlayableCandidateAndCancelsTheRemainingWork() throws Exception {
        URI rejected = URI.create("https://example.org/rejected.m3u8");
        URI playable = URI.create("https://example.org/playable.m3u8");
        URI slow = URI.create("https://example.org/slow.m3u8");
        List<URI> candidates = Arrays.asList(rejected, playable, slow);

        HlsCandidateRace.Result result = HlsCandidateRace.firstValid(
                candidates,
                3,
                2,
                new ResolutionDeadline(2_000L),
                0,
                candidates.size(),
                ResolutionProgressListener.NONE,
                candidate -> {
                    if (rejected.equals(candidate)) {
                        throw new IOException("not playable");
                    }
                    if (slow.equals(candidate)) {
                        try {
                            Thread.sleep(10_000L);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("cancelled", error);
                        }
                    }
                    return candidate;
                }
        );

        assertEquals(playable, result.getSource());
        assertTrue(result.getAttempted() <= 3);
    }

    @Test
    public void neverValidatesMoreThanTheConfiguredCandidateBudget() throws Exception {
        List<URI> candidates = Arrays.asList(
                URI.create("https://example.org/one.m3u8"),
                URI.create("https://example.org/two.m3u8"),
                URI.create("https://example.org/three.m3u8")
        );

        HlsCandidateRace.Result result = HlsCandidateRace.firstValid(
                candidates,
                2,
                1,
                new ResolutionDeadline(2_000L),
                0,
                2,
                ResolutionProgressListener.NONE,
                candidate -> {
                    throw new IOException("not playable");
                }
        );

        assertEquals(2, result.getAttempted());
        assertNull(result.getSource());
        assertNotNull(result.getLastError());
    }

    @Test(timeout = 3_000L)
    public void streamingRaceAcceptsAReadyCandidateBeforeAProducerFinishes() throws Exception {
        URI slow = URI.create("https://example.org/slow.m3u8");
        URI ready = URI.create("https://example.org/ready.m3u8");
        CountDownLatch slowStarted = new CountDownLatch(1);
        HlsCandidateRace.Streaming race = new HlsCandidateRace.Streaming(
                2,
                2,
                new ResolutionDeadline(2_000L),
                0,
                2,
                ResolutionProgressListener.NONE,
                candidate -> {
                    if (slow.equals(candidate)) {
                        slowStarted.countDown();
                        try {
                            slowStarted.await(5, TimeUnit.SECONDS);
                            Thread.sleep(5_000L);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("slow loser cancelled", error);
                        }
                    }
                    return ready.equals(candidate) ? candidate : null;
                }
        );
        try {
            race.submit(slow);
            race.submit(ready);
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
            HlsCandidateRace.Attempt attempt = race.poll(1_000L);
            assertNotNull(attempt);
            assertEquals(ready, attempt.getAccepted());
        } finally {
            race.close();
        }
    }

    @Test(timeout = 3_000L)
    public void closingStreamingRaceCancelsLoserContexts() throws Exception {
        URI slow = URI.create("https://example.org/slow.m3u8");
        URI ready = URI.create("https://example.org/ready.m3u8");
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowCancelled = new CountDownLatch(1);
        HlsCandidateRace.Streaming race = new HlsCandidateRace.Streaming(
                2,
                2,
                new ResolutionDeadline(2_000L),
                0,
                2,
                ResolutionProgressListener.NONE,
                candidate -> {
                    if (slow.equals(candidate)) {
                        slowStarted.countDown();
                        try {
                            while (true) {
                                ResolutionContext.current().check();
                                Thread.sleep(20L);
                            }
                        } catch (IOException | InterruptedException error) {
                            if (error instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            slowCancelled.countDown();
                            throw new IOException("slow loser cancelled", error);
                        }
                    }
                    return ready;
                }
        );
        try {
            race.submit(slow);
            race.submit(ready);
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
            HlsCandidateRace.Attempt attempt = race.poll(1_000L);
            assertNotNull(attempt);
            assertEquals(ready, attempt.getAccepted());
            race.cancel();
            assertTrue(slowCancelled.await(1, TimeUnit.SECONDS));
        } finally {
            race.close();
        }
    }
}
