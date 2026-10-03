package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Carrera rápida de TvVoo (2026-10-03).
 *
 * <p>Medición real: un enlace vivo contesta su lista HLS en 0,3–1 s y uno muerto se queda
 * colgado hasta el timeout. Antes se validaba de a 3 enlaces completos y los muertos
 * ocupaban los turnos. Ahora:</p>
 * <ol>
 *   <li>se piden los enlaces de varias versiones a la vez;</li>
 *   <li>a cada enlace se le pide solo su lista HLS («¿estás ahí?»), todos en paralelo;</li>
 *   <li>los que contestan pasan, en orden de llegada, a la prueba completa (segmento real),
 *       que además lee la resolución del video;</li>
 *   <li>al abrir ({@link Mode#PLAY}) gana el de mejor calidad entre los aceptados dentro de una
 *       ventana corta tras el primero; a igual calidad, la versión elegida en el editor y,
 *       dentro de una versión, NoFreeze (no vence) antes que Clean.</li>
 * </ol>
 * <p>{@link Mode#SCAN} recorre todo y devuelve un informe por versión para el selector.</p>
 */
final class TvVooFastRace {
    enum Mode { PLAY, SCAN }

    static final long QUALITY_WINDOW_MILLIS = 700L;
    private static final int PARALLEL_ALIASES = 4;
    private static final int PARALLEL_LIGHT = 12;
    private static final int PARALLEL_FULL = 3;

    /** Un enlace publicado por una versión del canal. */
    static final class Link {
        final String alias;
        final int aliasIndex;
        final URI published;
        final boolean noFreeze;

        Link(String alias, int aliasIndex, URI published) {
            this.alias = alias;
            this.aliasIndex = aliasIndex;
            this.published = published;
            String host = published == null || published.getHost() == null ? "" : published.getHost();
            this.noFreeze = host.endsWith("hayd.uk") && String.valueOf(published.getPath()).startsWith("/live/");
        }
    }

    /** Enlace que entregó video en la prueba completa. */
    static final class Accepted {
        final Link link;
        final URI source;
        final VideoSampleInfo info;
        final long respondedMillis;

        Accepted(Link link, URI source, VideoSampleInfo info, long respondedMillis) {
            this.link = link;
            this.source = source;
            this.info = info;
            this.respondedMillis = respondedMillis;
        }

        long score() {
            return info == null ? 0L : info.score();
        }
    }

    /** Estado de una versión al terminar (selector «Fuentes y calidades»). */
    static final class VersionReport {
        final String alias;
        final int aliasIndex;
        Accepted best;
        long respondedMillis = -1;
        String failure = "";
        /** Hubo una falla observada (no solo «no alcanzó a probarse»). */
        boolean failed;

        VersionReport(String alias, int aliasIndex) {
            this.alias = alias;
            this.aliasIndex = aliasIndex;
        }
    }

    static final class Result {
        final Accepted chosen;
        final List<VersionReport> versions;
        final IOException lastError;

        Result(Accepted chosen, List<VersionReport> versions, IOException lastError) {
            this.chosen = chosen;
            this.versions = versions;
            this.lastError = lastError;
        }
    }

    interface AliasQuery {
        List<URI> query(String alias) throws IOException;
    }

    /** Prueba liviana: solo la lista HLS. Devuelve la URI aceptada (http/https). */
    interface LightProbe {
        URI probe(URI published) throws IOException;
    }

    /** Prueba completa (segmento real); devuelve la calidad leída o null. */
    interface FullProbe {
        VideoSampleInfo probe(URI source) throws IOException;
    }

    interface Listener {
        void onLinkProgress(int tested, int total);

        Listener NONE = (tested, total) -> { };
    }

    private TvVooFastRace() {}

    /** Mejor de dos aceptados: calidad, versión elegida, NoFreeze y luego rapidez. */
    static int compare(Accepted left, Accepted right) {
        int quality = Long.compare(right.score(), left.score());
        if (quality != 0) return quality;
        int version = Integer.compare(left.link.aliasIndex, right.link.aliasIndex);
        if (version != 0) return version;
        if (left.link.noFreeze != right.link.noFreeze) return left.link.noFreeze ? -1 : 1;
        return Long.compare(left.respondedMillis, right.respondedMillis);
    }

    static Accepted best(List<Accepted> accepted) {
        Accepted best = null;
        for (Accepted candidate : accepted) {
            if (best == null || compare(candidate, best) < 0) best = candidate;
        }
        return best;
    }

    private static final class Event {
        final int kind; // 0 alias, 1 liviana, 2 completa
        final Link link;
        final String alias;
        final int aliasIndex;
        final List<URI> links;
        final URI accepted;
        final VideoSampleInfo info;
        final IOException error;
        final long elapsedMillis;

        Event(int kind, Link link, String alias, int aliasIndex, List<URI> links, URI accepted,
              VideoSampleInfo info, IOException error, long elapsedMillis) {
            this.kind = kind;
            this.link = link;
            this.alias = alias;
            this.aliasIndex = aliasIndex;
            this.links = links;
            this.accepted = accepted;
            this.info = info;
            this.error = error;
            this.elapsedMillis = elapsedMillis;
        }
    }

    static Result run(
            Mode mode,
            List<String> aliases,
            AliasQuery aliasQuery,
            LightProbe lightProbe,
            FullProbe fullProbe,
            ResolutionDeadline deadline,
            Listener listener
    ) throws IOException {
        Listener progress = listener == null ? Listener.NONE : listener;
        ResolutionContext parent = ResolutionContext.current();
        if (parent == null) parent = new ResolutionContext(deadline.remainingMillis());
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                PARALLEL_ALIASES + PARALLEL_LIGHT + PARALLEL_FULL,
                runnable -> {
                    Thread thread = new Thread(runnable, "vibem3u-tvvoo-race");
                    thread.setDaemon(true);
                    return thread;
                });
        List<Future<?>> futures = new ArrayList<>();
        List<ResolutionContext> contexts = new ArrayList<>();
        Map<String, VersionReport> reports = new LinkedHashMap<>();
        for (int index = 0; index < aliases.size(); index++) {
            reports.put(aliases.get(index), new VersionReport(aliases.get(index), index));
        }
        Deque<Integer> aliasQueue = new ArrayDeque<>();
        for (int index = 0; index < aliases.size(); index++) aliasQueue.add(index);
        Deque<Link> lightQueue = new ArrayDeque<>();
        Deque<Link> fullQueue = new ArrayDeque<>();
        Map<Link, URI> acceptedLight = new LinkedHashMap<>();
        Map<Link, Long> lightMillis = new LinkedHashMap<>();
        Set<URI> seen = new LinkedHashSet<>();
        List<Accepted> accepted = new ArrayList<>();
        int aliasInFlight = 0;
        int lightInFlight = 0;
        int fullInFlight = 0;
        int totalLinks = 0;
        int testedLinks = 0;
        long firstAcceptedAt = -1L;
        long started = System.nanoTime();
        IOException lastError = null;
        try {
            while (true) {
                // Lanza todo lo que cabe en cada etapa.
                while (aliasInFlight < PARALLEL_ALIASES && !aliasQueue.isEmpty()) {
                    int index = aliasQueue.poll();
                    String alias = aliases.get(index);
                    ResolutionContext child = parent.child(deadline.remainingMillis());
                    contexts.add(child);
                    futures.add(pool.submit(() -> {
                        try (ResolutionContext.Scope ignored = child.activate()) {
                            List<URI> links = aliasQuery.query(alias);
                            events.add(new Event(0, null, alias, index, links, null, null, null, 0));
                        } catch (IOException error) {
                            events.add(new Event(0, null, alias, index, Collections.emptyList(),
                                    null, null, error, 0));
                        } catch (RuntimeException error) {
                            events.add(new Event(0, null, alias, index, Collections.emptyList(), null,
                                    null, new IOException("No se pudo consultar la versión.", error), 0));
                        }
                    }));
                    aliasInFlight++;
                }
                while (lightInFlight < PARALLEL_LIGHT && !lightQueue.isEmpty()) {
                    Link link = lightQueue.poll();
                    ResolutionContext child = parent.child(deadline.remainingMillis());
                    contexts.add(child);
                    futures.add(pool.submit(() -> {
                        long begin = System.nanoTime();
                        try (ResolutionContext.Scope ignored = child.activate()) {
                            URI ok = lightProbe.probe(link.published);
                            events.add(new Event(1, link, null, 0, null, ok, null, null, millisSince(begin)));
                        } catch (IOException error) {
                            events.add(new Event(1, link, null, 0, null, null, null, error, millisSince(begin)));
                        } catch (RuntimeException error) {
                            events.add(new Event(1, link, null, 0, null, null, null,
                                    new IOException("Enlace TvVoo inválido.", error), millisSince(begin)));
                        }
                    }));
                    lightInFlight++;
                }
                while (fullInFlight < PARALLEL_FULL && !fullQueue.isEmpty()) {
                    Link link = fullQueue.poll();
                    URI source = acceptedLight.get(link);
                    ResolutionContext child = parent.child(deadline.remainingMillis());
                    contexts.add(child);
                    futures.add(pool.submit(() -> {
                        try (ResolutionContext.Scope ignored = child.activate()) {
                            VideoSampleInfo info = fullProbe.probe(source);
                            events.add(new Event(2, link, null, 0, null, source, info, null, 0));
                        } catch (IOException error) {
                            events.add(new Event(2, link, null, 0, null, null, null, error, 0));
                        } catch (RuntimeException error) {
                            events.add(new Event(2, link, null, 0, null, null, null,
                                    new IOException("Fuente TvVoo inválida.", error), 0));
                        }
                    }));
                    fullInFlight++;
                }

                boolean idle = aliasInFlight == 0 && lightInFlight == 0 && fullInFlight == 0
                        && aliasQueue.isEmpty() && lightQueue.isEmpty() && fullQueue.isEmpty();
                if (mode == Mode.PLAY && !accepted.isEmpty()) {
                    long waited = millisSince(started) - firstAcceptedAt;
                    if (idle || waited >= QUALITY_WINDOW_MILLIS) break;
                }
                if (idle) break;
                if (deadline.remainingMillis() <= 0L) {
                    if (!accepted.isEmpty()) break;
                    deadline.check();
                }
                parent.check();

                long wait = Math.max(1L, Math.min(200L, deadline.remainingMillis()));
                if (mode == Mode.PLAY && firstAcceptedAt >= 0L) {
                    wait = Math.max(1L, Math.min(wait,
                            QUALITY_WINDOW_MILLIS - (millisSince(started) - firstAcceptedAt)));
                }
                Event event;
                try {
                    event = events.poll(wait, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", interrupted);
                }
                if (event == null) continue;

                if (event.kind == 0) {
                    aliasInFlight--;
                    VersionReport report = reports.get(event.alias);
                    if (event.error != null || event.links.isEmpty()) {
                        if (event.error != null) lastError = event.error;
                        report.failure = "Sin enlaces ahora";
                        report.failed = true;
                    }
                    for (URI uri : event.links) {
                        if (uri == null || !seen.add(uri)) continue;
                        Link link = new Link(event.alias, event.aliasIndex, uri);
                        lightQueue.add(link);
                        totalLinks++;
                    }
                    progress.onLinkProgress(testedLinks, totalLinks);
                } else if (event.kind == 1) {
                    lightInFlight--;
                    testedLinks++;
                    progress.onLinkProgress(testedLinks, totalLinks);
                    VersionReport report = reports.get(event.link.alias);
                    if (event.error != null) {
                        lastError = event.error;
                        if (report.best == null && report.respondedMillis < 0) {
                            report.failure = failureText(event.error);
                            report.failed = true;
                        }
                        continue;
                    }
                    acceptedLight.put(event.link, event.accepted);
                    lightMillis.put(event.link, event.elapsedMillis);
                    if (report.respondedMillis < 0 || event.elapsedMillis < report.respondedMillis) {
                        report.respondedMillis = event.elapsedMillis;
                    }
                    fullQueue.add(event.link);
                } else {
                    fullInFlight--;
                    VersionReport report = reports.get(event.link.alias);
                    if (event.error != null) {
                        lastError = event.error;
                        if (report.best == null) {
                            report.failure = "Sin video ahora";
                            report.failed = true;
                        }
                        continue;
                    }
                    Long responded = lightMillis.get(event.link);
                    Accepted candidate = new Accepted(event.link, event.accepted, event.info,
                            responded == null ? Long.MAX_VALUE : responded);
                    accepted.add(candidate);
                    if (report.best == null || compare(candidate, report.best) < 0) report.best = candidate;
                    report.failure = "";
                    if (firstAcceptedAt < 0L) firstAcceptedAt = millisSince(started);
                }
            }
        } finally {
            for (ResolutionContext context : contexts) context.cancel();
            for (Future<?> future : futures) future.cancel(true);
            pool.shutdownNow();
        }
        for (VersionReport report : reports.values()) {
            if (report.best == null && report.failure.isEmpty()) report.failure = "No respondió";
        }
        Accepted chosen = best(accepted);
        return new Result(chosen, new ArrayList<>(reports.values()), lastError);
    }

    private static String failureText(IOException error) {
        String message = String.valueOf(error.getMessage());
        if (error instanceof TokenHttpClient.HttpStatusException) {
            int status = ((TokenHttpClient.HttpStatusException) error).getStatusCode();
            return status >= 500 ? "Error del servidor" : "Rechazada (" + status + ")";
        }
        if (message.contains("timed out") || message.contains("Timeout") || message.contains("timeout")) {
            return "No respondió";
        }
        return "Sin señal ahora";
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
