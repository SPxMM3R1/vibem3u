package cl.streambox.tv;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Calidad real del segmento y carrera rápida de TvVoo (2026-10-03). */
public final class TvVooQualityTest {
    @After
    public void reset() {
        TvVooDeadVersions.resetForTests();
    }

    @Test
    public void readsResolutionAndFpsFromRealTransportStreams() throws IOException {
        VideoSampleInfo full = VideoSampleInfo.probe(resource("video/h264_1080p60.ts"));
        assertNotNull(full);
        assertEquals(1920, full.width);
        assertEquals(1080, full.height);
        assertEquals(60, full.fps);
        assertEquals("1080p · 60 fps", full.label());

        VideoSampleInfo small = VideoSampleInfo.probe(resource("video/h264_270p30.ts"));
        assertNotNull(small);
        assertEquals(480, small.width);
        assertEquals(270, small.height);
        assertEquals(30, small.fps);
        assertTrue(full.score() > small.score());
    }

    @Test
    public void readsHevcResolution() throws IOException {
        VideoSampleInfo hevc = VideoSampleInfo.probe(resource("video/hevc_1080_sps.bin"));
        assertNotNull(hevc);
        assertEquals(1920, hevc.width);
        assertEquals(1080, hevc.height);
        assertEquals("hevc", hevc.codec);
        assertEquals("1080p", hevc.label());
    }

    @Test
    public void unknownDataHasNoQuality() {
        assertNull(VideoSampleInfo.probe(new byte[]{1, 2, 3}));
        assertNull(VideoSampleInfo.probe(new byte[188 * 4]));
    }

    @Test
    public void playOpensWithTheFirstStableAnswerWithoutWaitingForQuality() throws IOException {
        String stable = "https://tvvoo.hayd.uk/live/manifest.m3u8?url=a";
        Map<String, List<URI>> links = new HashMap<>();
        links.put("elegida", Collections.singletonList(URI.create(stable)));
        links.put("hermana", Collections.singletonList(URI.create("http://1.1.1.2/b.m3u8")));
        Map<String, VideoSampleInfo> quality = new HashMap<>();
        quality.put(stable, new VideoSampleInfo(1024, 576, 25, "h264"));
        quality.put("http://1.1.1.2/b.m3u8", new VideoSampleInfo(1920, 1080, 50, "h264"));
        // La de mejor calidad tarda: antes se la esperaba 700 ms; desde 0.5.87 se abre ya.
        Map<String, Long> slow = Collections.singletonMap("http://1.1.1.2/b.m3u8", 3_000L);
        long started = System.nanoTime();

        TvVooFastRace.Result result = run(TvVooFastRace.Mode.PLAY, Arrays.asList("elegida", "hermana"),
                links, slow, quality);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals("elegida", result.chosen.link.alias);
        assertTrue("No espera la ventana de calidad: " + elapsedMs + " ms",
                elapsedMs < TvVooFastRace.QUALITY_WINDOW_MILLIS);
    }

    @Test
    public void scanStillRanksTheBestQualityForTheUpgradeOffer() throws IOException {
        Map<String, List<URI>> links = new HashMap<>();
        links.put("elegida", Collections.singletonList(URI.create("http://1.1.1.1/a.m3u8")));
        links.put("hermana", Collections.singletonList(URI.create("http://1.1.1.2/b.m3u8")));
        Map<String, VideoSampleInfo> quality = new HashMap<>();
        quality.put("http://1.1.1.1/a.m3u8", new VideoSampleInfo(1024, 576, 25, "h264"));
        quality.put("http://1.1.1.2/b.m3u8", new VideoSampleInfo(1920, 1080, 50, "h264"));

        TvVooFastRace.Result result = run(TvVooFastRace.Mode.SCAN, Arrays.asList("elegida", "hermana"),
                links, Collections.emptyMap(), quality);

        assertEquals("hermana", result.chosen.link.alias);
        assertEquals(1080, result.chosen.info.height);
    }

    @Test
    public void sameQualityKeepsTheEditorVersionAndPrefersNoFreeze() throws IOException {
        Map<String, List<URI>> links = new HashMap<>();
        links.put("elegida", Arrays.asList(
                URI.create("http://1.1.1.1/sunshine/a.m3u8"),
                URI.create("https://tvvoo.hayd.uk/live/manifest.m3u8?url=x")));
        links.put("hermana", Collections.singletonList(URI.create("http://1.1.1.2/b.m3u8")));
        VideoSampleInfo hd = new VideoSampleInfo(1280, 720, 25, "h264");
        Map<String, VideoSampleInfo> quality = new HashMap<>();
        quality.put("http://1.1.1.1/sunshine/a.m3u8", hd);
        quality.put("https://tvvoo.hayd.uk/live/manifest.m3u8?url=x", hd);
        quality.put("http://1.1.1.2/b.m3u8", hd);

        TvVooFastRace.Result result = run(TvVooFastRace.Mode.PLAY, Arrays.asList("elegida", "hermana"),
                links, Collections.emptyMap(), quality);

        assertEquals("elegida", result.chosen.link.alias);
        assertTrue(result.chosen.link.noFreeze);
    }

    @Test
    public void deadLinksDoNotBlockTheLiveOne() throws IOException {
        Map<String, List<URI>> links = new HashMap<>();
        links.put("muerta", Collections.singletonList(URI.create("http://1.1.1.9/dead.m3u8")));
        links.put("viva", Collections.singletonList(URI.create("http://1.1.1.2/ok.m3u8")));
        Map<String, Long> slow = Collections.singletonMap("http://1.1.1.9/dead.m3u8", 5_000L);
        long started = System.nanoTime();

        TvVooFastRace.Result result = run(TvVooFastRace.Mode.PLAY, Arrays.asList("muerta", "viva"),
                links, slow, Collections.emptyMap());

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals("viva", result.chosen.link.alias);
        assertTrue("No espera al enlace colgado: " + elapsedMs + " ms", elapsedMs < 3_000L);
    }

    @Test
    public void scanReportsEveryVersionIncludingTheSilentOnes() throws IOException {
        Map<String, List<URI>> links = new HashMap<>();
        links.put("viva", Collections.singletonList(URI.create("http://1.1.1.2/ok.m3u8")));
        links.put("vacia", Collections.emptyList());

        TvVooFastRace.Result result = run(TvVooFastRace.Mode.SCAN, Arrays.asList("viva", "vacia"),
                links, Collections.emptyMap(), Collections.emptyMap());

        assertEquals(2, result.versions.size());
        TvVooFastRace.VersionReport empty = result.versions.get(1);
        assertNull(empty.best);
        assertTrue(empty.failed);
        assertFalse(empty.failure.isEmpty());
        assertNotNull(result.versions.get(0).best);
    }

    @Test
    public void upgradeSearchStopsAtTheFirstBetterVersionWithoutWaitingForSilentOnes() throws IOException {
        Map<String, List<URI>> links = new HashMap<>();
        links.put("actual", Collections.singletonList(URI.create("http://1.1.1.1/a.m3u8")));
        links.put("colgada", Collections.singletonList(URI.create("http://1.1.1.9/dead.m3u8")));
        links.put("fhd", Collections.singletonList(URI.create("http://1.1.1.2/b.m3u8")));
        Map<String, VideoSampleInfo> quality = new HashMap<>();
        quality.put("http://1.1.1.1/a.m3u8", new VideoSampleInfo(1280, 720, 25, "h264"));
        quality.put("http://1.1.1.2/b.m3u8", new VideoSampleInfo(1920, 1080, 25, "h264"));
        Map<String, Long> slow = Collections.singletonMap("http://1.1.1.9/dead.m3u8", 6_000L);
        long started = System.nanoTime();

        TvVooFastRace.Result result = run(TvVooFastRace.Mode.SCAN,
                Arrays.asList("actual", "colgada", "fhd"), links, slow, quality, 720);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals("fhd", result.chosen.link.alias);
        assertTrue("No espera a la versión colgada: " + elapsedMs + " ms", elapsedMs < 3_000L);
    }

    @Test
    public void upgradeSearchWaitsBrieflyForA1080pThatAnswersAfterA720p() throws IOException {
        Map<String, List<URI>> links = new HashMap<>();
        links.put("actual", Collections.singletonList(URI.create("http://1.1.1.1/a.m3u8")));
        links.put("hd", Collections.singletonList(URI.create("http://1.1.1.2/hd.m3u8")));
        links.put("fhd", Collections.singletonList(URI.create("http://1.1.1.3/fhd.m3u8")));
        Map<String, VideoSampleInfo> quality = new HashMap<>();
        quality.put("http://1.1.1.1/a.m3u8", new VideoSampleInfo(854, 480, 25, "h264"));
        quality.put("http://1.1.1.2/hd.m3u8", new VideoSampleInfo(1280, 720, 25, "h264"));
        quality.put("http://1.1.1.3/fhd.m3u8", new VideoSampleInfo(1920, 1080, 25, "h264"));
        // La 1080p contesta 1 s después que la 720p: igual entra en la búsqueda.
        Map<String, Long> delays = Collections.singletonMap("http://1.1.1.3/fhd.m3u8", 1_000L);

        TvVooFastRace.Result result = runDelayed(TvVooFastRace.Mode.SCAN,
                Arrays.asList("actual", "hd", "fhd"), links, delays, quality, 480);

        TvVooFastRace.VersionReport fhd = result.versions.get(2);
        assertNotNull("La 1080p tardía debe quedar en el informe", fhd.best);
        assertEquals(1080, fhd.best.info.height);
    }

    @Test
    public void playWaitsBrieflyForThePinnedVersionInsteadOfTheFastestSibling() throws IOException {
        Map<String, List<URI>> links = new HashMap<>();
        links.put("elegida", Collections.singletonList(
                URI.create("https://tvvoo.hayd.uk/live/manifest.m3u8?url=pin")));
        links.put("rapida", Collections.singletonList(
                URI.create("https://tvvoo.hayd.uk/live/manifest.m3u8?url=fast")));
        // La elegida contesta 300 ms después que la hermana: igual gana (memoria del selector).
        Map<String, Long> delays = Collections.singletonMap(
                "https://tvvoo.hayd.uk/live/manifest.m3u8?url=pin", 300L);

        TvVooFastRace.Result result = runDelayed(TvVooFastRace.Mode.PLAY,
                Arrays.asList("elegida", "rapida"), links, delays, Collections.emptyMap(), -1);

        assertEquals("elegida", result.chosen.link.alias);
    }

    @Test
    public void deadVersionsGoLastForTenMinutes() {
        long now = 1_000_000L;
        TvVooDeadVersions.markDead("canal", "a", now);
        List<String> order = TvVooDeadVersions.order("canal", Arrays.asList("a", "b", "c"), 8, now + 1);
        assertEquals(Arrays.asList("b", "c", "a"), order);
        List<String> later = TvVooDeadVersions.order("canal", Arrays.asList("a", "b", "c"), 8,
                now + TvVooDeadVersions.DEAD_FOR_MILLIS + 1);
        assertEquals(Arrays.asList("a", "b", "c"), later);
    }

    @Test
    public void versionNamesAreReadable() {
        assertEquals("TNT SPORTS 3 HD",
                TvVooStreamResolver.versionName("vavoo_TNT%20SPORTS%203%20HD%7Cgroup%3Auk"));
    }

    private static TvVooFastRace.Result run(
            TvVooFastRace.Mode mode,
            List<String> aliases,
            Map<String, List<URI>> links,
            Map<String, Long> lightDelayMillis,
            Map<String, VideoSampleInfo> quality
    ) throws IOException {
        return run(mode, aliases, links, lightDelayMillis, quality, -1);
    }

    private static TvVooFastRace.Result run(
            TvVooFastRace.Mode mode,
            List<String> aliases,
            Map<String, List<URI>> links,
            Map<String, Long> lightDelayMillis,
            Map<String, VideoSampleInfo> quality,
            int stopAboveHeight
    ) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ResolutionContext context = new ResolutionContext(10_000L);
        try (ResolutionContext.Scope ignored = context.activate()) {
            return TvVooFastRace.run(
                    mode,
                    aliases,
                    alias -> {
                        calls.incrementAndGet();
                        return links.getOrDefault(alias, Collections.emptyList());
                    },
                    published -> {
                        Long delay = lightDelayMillis.get(published.toString());
                        if (delay != null) {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("cancelada");
                            }
                            throw new IOException("Read timed out");
                        }
                        return published;
                    },
                    source -> quality.get(source.toString()),
                    new ResolutionDeadline(8_000L),
                    TvVooFastRace.Listener.NONE,
                    stopAboveHeight
            );
        }
    }

    /** Como {@link #run}, pero los enlaces lentos contestan bien después de la demora. */
    private static TvVooFastRace.Result runDelayed(
            TvVooFastRace.Mode mode,
            List<String> aliases,
            Map<String, List<URI>> links,
            Map<String, Long> delays,
            Map<String, VideoSampleInfo> quality,
            int stopAboveHeight
    ) throws IOException {
        ResolutionContext context = new ResolutionContext(10_000L);
        try (ResolutionContext.Scope ignored = context.activate()) {
            return TvVooFastRace.run(
                    mode,
                    aliases,
                    alias -> links.getOrDefault(alias, Collections.emptyList()),
                    published -> {
                        Long delay = delays.get(published.toString());
                        if (delay != null) {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("cancelada");
                            }
                        }
                        return published;
                    },
                    source -> quality.get(source.toString()),
                    new ResolutionDeadline(8_000L),
                    TvVooFastRace.Listener.NONE,
                    stopAboveHeight
            );
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = TvVooQualityTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("Falta " + name, input);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
