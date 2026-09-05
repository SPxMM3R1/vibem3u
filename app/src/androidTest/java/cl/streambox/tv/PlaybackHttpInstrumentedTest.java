package cl.streambox.tv;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import static org.junit.Assert.*;

/** Deterministic HTTP/decoder smoke tests. The tiny video is generated from a solid colour in CI. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class PlaybackHttpInstrumentedTest {
    @Test public void aDirectHttpChannelRendersAFrameAndRecordsOpeningTime() throws Exception {
        runPlayback(false);
    }

    @Test public void rejectedSourceRenewsOnceAndRendersWithoutRetryingTheRejectedToken() throws Exception {
        runPlayback(true);
    }

    private void runPlayback(boolean rejectFirstSource) throws Exception {
        byte[] video;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("startup-test.mp4")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            video = output.toByteArray();
        }
        AtomicInteger rejectedRequests = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/expired")) {
                    rejectedRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(403);
                }
                return new MockResponse().setHeader("Content-Type", "video/mp4")
                        .setBody(new Buffer().write(video));
            }
        });
        server.start();
        CountDownLatch frame = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        AtomicReference<PlaybackBufferManager> bufferManagerRef = new AtomicReference<>();
        AtomicReference<Surface> surfaceRef = new AtomicReference<>();
        AtomicReference<SurfaceTexture> textureRef = new AtomicReference<>();
        AtomicInteger renewals = new AtomicInteger();
        PlaybackStartupMetrics metrics = new PlaybackStartupMetrics();
        PlaybackRecoveryEpisode episode = new PlaybackRecoveryEpisode();
        String fresh = server.url("/fresh.mp4").toString();
        String initial = rejectFirstSource ? server.url("/expired.mp4").toString() : fresh;
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                SurfaceTexture texture = new SurfaceTexture(0);
                texture.setDefaultBufferSize(160, 96);
                textureRef.set(texture);
                Surface surface = new Surface(texture);
                surfaceRef.set(surface);
                DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(
                        new OkHttpDataSource.Factory(SharedHttpClient.get()))
                        .setLoadErrorHandlingPolicy(new PlaybackLoadErrorPolicy(rejectFirstSource));
                PlaybackBufferManager bufferManager = new PlaybackBufferManager(128L * 1024 * 1024, true);
                bufferManagerRef.set(bufferManager);
                ExoPlayer player = new ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()).setMediaSourceFactory(sources)
                        .setLoadControl(bufferManager.loadControl()).build();
                playerRef.set(player);
                bufferManager.attach(player, metrics);
                player.setVideoSurface(surface);
                player.addAnalyticsListener(new PlaybackStartupAnalytics(metrics));
                player.addAnalyticsListener(new AnalyticsListener() {
                    @Override public void onRenderedFirstFrame(EventTime time, Object output, long timeMs) {
                        frame.countDown();
                    }
                });
                player.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException error) {
                        if (rejectFirstSource && episode.tryRefresh()) {
                            renewals.incrementAndGet();
                            metrics.failed(metrics.currentId());
                            long attempt = metrics.begin("test", PlaybackStartupMetrics.Reason.REFRESH);
                            player.setMediaItem(item(fresh, attempt));
                            player.prepare();
                            player.play();
                        } else {
                            failure.set(error);
                            frame.countDown();
                        }
                    }
                });
                long attempt = metrics.begin("test", PlaybackStartupMetrics.Reason.CHANNEL);
                metrics.dequeued(attempt);
                metrics.resolved(attempt);
                player.setMediaItem(item(initial, attempt));
                player.prepare();
                player.play();
            });
            assertTrue("No first frame within the smoke-test budget", frame.await(20, TimeUnit.SECONDS));
            assertNull("Playback failed", failure.get());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(metrics.snapshot().firstFrameMs >= 0L);
            assertEquals(rejectFirstSource ? 1 : 0, renewals.get());
            assertEquals(rejectFirstSource ? 1 : 0, rejectedRequests.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MediaItem current = playerRef.get().getCurrentMediaItem();
                bufferManagerRef.get().onMemoryPressure(80);
                assertSame("Memory pressure must not replace the playing source", current,
                        playerRef.get().getCurrentMediaItem());
                assertNull(playerRef.get().getPlayerError());
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (bufferManagerRef.get() != null) bufferManagerRef.get().close();
                if (playerRef.get() != null) playerRef.get().release();
                if (surfaceRef.get() != null) surfaceRef.get().release();
                if (textureRef.get() != null) textureRef.get().release();
            });
            server.shutdown();
        }
    }

    private static MediaItem item(String uri, long attempt) {
        return new MediaItem.Builder().setUri(uri).setMediaId("synthetic-video")
                .setTag(Long.valueOf(attempt)).build();
    }
}
