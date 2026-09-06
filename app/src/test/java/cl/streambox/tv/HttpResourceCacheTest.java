package cl.streambox.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HttpResourceCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistsBodyAndValidatorsAcrossCacheInstances() throws Exception {
        File directory = temporaryFolder.newFolder("resource-cache");
        String url = "https://example.test/epg.xml";
        byte[] body = "<tv/>".getBytes(StandardCharsets.UTF_8);

        HttpResourceCache first = new HttpResourceCache(directory, 3, 1024);
        first.commit(
                url,
                new HttpResourceCache.FetchResult(
                        new HttpResourceCache.CachedResource(body, "etag-1", "last-1"),
                        true,
                        true
                ),
                body
        );

        HttpResourceCache second = new HttpResourceCache(directory, 3, 1024);
        HttpResourceCache.CachedResource cached = second.readCached(url, 1024);

        assertNotNull(cached);
        assertArrayEquals(body, cached.getBytes());
        assertEquals("etag-1", cached.getEtag());
        assertEquals("last-1", cached.getLastModified());
        org.junit.Assert.assertTrue(cached.getCheckedAtMillis() > 0L);
    }

    @Test
    public void rewriteKeepsExistingValidators() throws Exception {
        File directory = temporaryFolder.newFolder("rewrite-cache");
        String url = "https://example.test/m3u.m3u";
        byte[] original = "#EXTM3U\nold".getBytes(StandardCharsets.UTF_8);
        byte[] rewritten = "#EXTM3U\nnew".getBytes(StandardCharsets.UTF_8);

        HttpResourceCache cache = new HttpResourceCache(directory, 3, 1024);
        cache.commit(
                url,
                new HttpResourceCache.FetchResult(
                        new HttpResourceCache.CachedResource(original, "etag-2", "last-2"),
                        true,
                        true
                ),
                original
        );
        cache.rewriteCached(url, rewritten);

        HttpResourceCache.CachedResource cached = cache.readCached(url, 1024);
        assertNotNull(cached);
        assertArrayEquals(rewritten, cached.getBytes());
        assertEquals("etag-2", cached.getEtag());
        assertEquals("last-2", cached.getLastModified());
    }

    @Test
    public void rewrittenBodyWithoutMetadataPersistsAcrossInstances() throws Exception {
        File directory = temporaryFolder.newFolder("render-cache");
        String key = "render-v1:https://example.test/logo.svg#156x108";
        byte[] rendered = new byte[]{1, 2, 3, 4};

        HttpResourceCache first = new HttpResourceCache(directory, 3, 1024);
        first.rewriteCached(key, rendered);

        HttpResourceCache second = new HttpResourceCache(directory, 3, 1024);
        HttpResourceCache.CachedResource cached = second.readCached(key, 1024);

        assertNotNull(cached);
        assertArrayEquals(rendered, cached.getBytes());
    }

    @Test(timeout = 5_000L)
    public void cachedReadDoesNotWaitForARevalidationHeldByTheServer() throws Exception {
        File directory = temporaryFolder.newFolder("held-server-cache");
        HttpResourceCache cache = new HttpResourceCache(directory, 3, 1024);
        byte[] cachedBody = "cached".getBytes(StandardCharsets.UTF_8);

        try (BlockingHttpServer server = new BlockingHttpServer()) {
            String url = server.url("/cached");
            // Move the fixture to the URL served by the local test server.
            cache.commit(
                    url,
                    new HttpResourceCache.FetchResult(
                            new HttpResourceCache.CachedResource(cachedBody, "etag", "last"),
                            true,
                            true
                    ),
                    cachedBody
            );
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<HttpResourceCache.FetchResult> fetch = executor.submit(
                        () -> cache.fetch(url, 1024, "test", "*/*")
                );
                assertTrue(server.requestSeen.await(2, TimeUnit.SECONDS));

                // A second caller joins the same request instead of opening
                // another retained socket or racing a second cache commit.
                Future<HttpResourceCache.FetchResult> joinedFetch = executor.submit(
                        () -> cache.fetch(url, 1024, "test", "*/*")
                );

                // This is the fast-start path: it must remain available while
                // the revalidation socket is deliberately held open.
                HttpResourceCache.CachedResource local = cache.readCached(url, 1024);
                assertNotNull(local);
                assertArrayEquals(cachedBody, local.getBytes());

                server.release();
                assertNotNull(fetch.get(2, TimeUnit.SECONDS));
                assertNotNull(joinedFetch.get(2, TimeUnit.SECONDS));
                assertEquals(1, server.requestCount.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test(timeout = 5_000L)
    public void differentUrlsCanRevalidateInParallel() throws Exception {
        File directory = temporaryFolder.newFolder("parallel-cache");
        HttpResourceCache cache = new HttpResourceCache(directory, 4, 2048);
        try (BlockingHttpServer server = new BlockingHttpServer()) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<HttpResourceCache.FetchResult> first = executor.submit(
                        () -> cache.fetch(server.url("/one"), 1024, "test", "*/*")
                );
                Future<HttpResourceCache.FetchResult> second = executor.submit(
                        () -> cache.fetch(server.url("/two"), 1024, "test", "*/*")
                );

                assertTrue(server.requestsSeen.await(2, TimeUnit.SECONDS));
                server.release();
                assertNotNull(first.get(2, TimeUnit.SECONDS));
                assertNotNull(second.get(2, TimeUnit.SECONDS));
                assertEquals(2, server.requestCount.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static final class BlockingHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService clients = Executors.newCachedThreadPool();
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch requestsSeen = new CountDownLatch(2);
        private final CountDownLatch requestSeen = new CountDownLatch(1);
        private final AtomicInteger requestCount = new AtomicInteger();
        private final Thread acceptThread;

        private BlockingHttpServer() throws Exception {
            serverSocket = new ServerSocket(0);
            acceptThread = new Thread(this::acceptClients, "cache-test-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private String url(String path) {
            return URI.create("http://127.0.0.1:" + serverSocket.getLocalPort() + path).toString();
        }

        private void acceptClients() {
            try {
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    clients.submit(() -> serve(socket));
                }
            } catch (Exception ignored) {
                // Closing the server ends the accept loop.
            }
        }

        private void serve(Socket socket) {
            try (Socket connection = socket;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                OutputStream output = connection.getOutputStream()) {
                String requestLine = reader.readLine();
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    // Consume the request headers before releasing the response.
                }
                int count = requestCount.incrementAndGet();
                requestSeen.countDown();
                requestsSeen.countDown();
                release.await(2, TimeUnit.SECONDS);

                if (requestLine != null && requestLine.contains("/cached")) {
                    output.write(("HTTP/1.1 304 Not Modified\r\n"
                            + "ETag: etag\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] body = ("body-" + count).getBytes(StandardCharsets.UTF_8);
                    output.write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(body);
                }
                output.flush();
            } catch (Exception ignored) {
                // The test reports the client-side timeout or failed future.
            }
        }

        private void release() {
            release.countDown();
        }

        @Override
        public void close() throws Exception {
            release();
            serverSocket.close();
            clients.shutdownNow();
            acceptThread.join(1_000L);
        }
    }
}
