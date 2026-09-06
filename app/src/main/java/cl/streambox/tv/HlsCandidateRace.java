package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Runs a bounded race for HLS candidates and cancels the losers. */
final class HlsCandidateRace {
    interface CandidateValidator {
        URI validate(URI candidate) throws IOException;
    }

    /**
     * Validates a finite list while returning as soon as one candidate wins.
     * The streaming implementation is also used by TvVoo while aliases are
     * still being fetched, keeping cancellation and context propagation the
     * same for both providers.
     */
    static Result firstValid(
            List<URI> candidates,
            int maximumCandidates,
            int parallelism,
            ResolutionDeadline deadline,
            int progressOffset,
            int progressTotal,
            ResolutionProgressListener listener,
            CandidateValidator validator
    ) throws IOException {
        if (candidates == null || candidates.isEmpty()) {
            return new Result(null, 0, null);
        }
        if (deadline == null) throw new IOException("Presupuesto de resolución ausente.");
        if (validator == null) throw new IOException("Validador de fuente ausente.");

        List<URI> unique = uniqueCandidates(candidates, maximumCandidates);
        if (unique.isEmpty()) return new Result(null, 0, null);

        Streaming streaming = new Streaming(
                maximumCandidates,
                parallelism,
                deadline,
                progressOffset,
                progressTotal,
                listener,
                validator
        );
        IOException lastError = null;
        int nextIndex = 0;
        try {
            while (nextIndex < unique.size() && streaming.hasCapacity()) {
                deadline.check();
                streaming.submit(unique.get(nextIndex++));
            }
            while (streaming.hasInFlight()) {
                deadline.check();
                Attempt attempt = streaming.poll(deadline.remainingMillis());
                if (attempt == null) throw new IOException("Tiempo de resolución agotado.");
                if (attempt.accepted != null) {
                    streaming.cancel();
                    return new Result(attempt.accepted, nextIndex, lastError);
                }
                if (attempt.error != null) lastError = attempt.error;
                while (nextIndex < unique.size() && streaming.hasCapacity()) {
                    deadline.check();
                    streaming.submit(unique.get(nextIndex++));
                }
            }
        } finally {
            streaming.close();
        }
        return new Result(null, nextIndex, lastError);
    }

    private static List<URI> uniqueCandidates(
            List<URI> candidates,
            int maximumCandidates
    ) {
        List<URI> unique = new ArrayList<>();
        Set<URI> seen = new LinkedHashSet<>();
        int limit = Math.max(1, maximumCandidates);
        for (URI candidate : candidates) {
            if (candidate != null && seen.add(candidate)) {
                unique.add(candidate);
                if (unique.size() >= limit) break;
            }
        }
        return unique;
    }

    /**
     * Incremental candidate race. A producer may submit candidates whenever
     * they become available and poll completed validations without waiting for
     * a producer batch. All submitted candidates share the deadline but each
     * gets a child context, so cancelling losers cannot cancel a sibling path
     * owned by the caller.
     */
    static final class Streaming implements AutoCloseable {
        private final int maximumCandidates;
        private final ResolutionDeadline deadline;
        private final ResolutionProgressListener progress;
        private final int progressOffset;
        private final int progressTotal;
        private final CandidateValidator validator;
        private final CompletionService<Attempt> completion;
        private final ExecutorService executor;
        private final ResolutionContext raceContext;
        private final List<Future<Attempt>> submitted = new ArrayList<>();
        private final Set<URI> seen = new LinkedHashSet<>();
        private int submittedCount;
        private int completedCount;
        private boolean cancelled;

        Streaming(
                int maximumCandidates,
                int parallelism,
                ResolutionDeadline deadline,
                int progressOffset,
                int progressTotal,
                ResolutionProgressListener listener,
                CandidateValidator validator
        ) throws IOException {
            if (deadline == null) throw new IOException("Presupuesto de resolución ausente.");
            if (validator == null) throw new IOException("Validador de fuente ausente.");
            this.maximumCandidates = Math.max(1, maximumCandidates);
            this.deadline = deadline;
            this.progressOffset = Math.max(0, progressOffset);
            this.progressTotal = progressTotal;
            this.progress = listener == null
                    ? ResolutionProgressListener.NONE
                    : listener;
            this.validator = validator;
            int workers = Math.max(1, Math.min(
                    Math.max(1, parallelism),
                    this.maximumCandidates
            ));
            this.executor = Executors.newFixedThreadPool(
                    workers,
                    new DaemonThreadFactory()
            );
            ResolutionContext parent = ResolutionContext.current();
            this.raceContext = parent == null
                    ? new ResolutionContext(deadline.remainingMillis())
                    : parent.child(deadline.remainingMillis());
            this.completion = new ExecutorCompletionService<>(executor);
        }

        boolean hasCapacity() {
            return !cancelled && submittedCount < maximumCandidates;
        }

        boolean hasInFlight() {
            return completedCount < submittedCount;
        }

        int getSubmittedCount() {
            return submittedCount;
        }

        /** Returns false for null/duplicate/over-budget candidates. */
        boolean submit(URI candidate) throws IOException {
            if (!hasCapacity() || candidate == null || !seen.add(candidate)) return false;
            deadline.check();
            int displayIndex = progressOffset + submittedCount + 1;
            int displayTotal = progressTotal > 0
                    ? progressTotal
                    : progressOffset + maximumCandidates;
            progress.onProgress(ResolutionProgress.counted(
                    ResolutionStage.SOURCE_CANDIDATE,
                    displayIndex,
                    displayTotal,
                    "GET " + SafePlaybackText.url(candidate)
                            + " · playlist/variante/segmento"
            ));

            final ResolutionContext candidateContext = raceContext.child(
                    deadline.remainingMillis()
            );
            Callable<Attempt> task = () -> {
                try (ResolutionContext.Scope ignored = candidateContext.activate()) {
                    deadline.check();
                    ResolutionContext.current().check();
                    return new Attempt(validator.validate(candidate), null);
                } catch (IOException error) {
                    return new Attempt(null, error);
                }
            };
            Future<Attempt> future = completion.submit(ResolutionContext.wrapCurrent(task));
            submitted.add(future);
            submittedCount++;
            return true;
        }

        /** Null means no completion arrived before the supplied wait period. */
        Attempt poll(long waitMillis) throws IOException {
            if (!hasInFlight()) return null;
            Future<Attempt> future;
            try {
                future = completion.poll(
                        Math.max(1L, waitMillis),
                        TimeUnit.MILLISECONDS
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Solicitud cancelada.", error);
            }
            if (future == null) return null;
            completedCount++;
            try {
                return future.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Solicitud cancelada.", error);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                return new Attempt(
                        null,
                        cause instanceof IOException
                                ? (IOException) cause
                                : new IOException("No se pudo validar la fuente.", cause)
                );
            } catch (CancellationException error) {
                return new Attempt(null, new IOException("Solicitud cancelada.", error));
            }
        }

        void cancel() {
            if (cancelled) return;
            cancelled = true;
            raceContext.cancel();
            for (Future<Attempt> future : submitted) {
                if (!future.isDone()) future.cancel(true);
            }
        }

        @Override
        public void close() {
            cancel();
            executor.shutdownNow();
            try {
                executor.awaitTermination(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class Result {
        private final URI source;
        private final int attempted;
        private final IOException lastError;

        Result(URI source, int attempted, IOException lastError) {
            this.source = source;
            this.attempted = Math.max(0, attempted);
            this.lastError = lastError;
        }

        URI getSource() { return source; }
        int getAttempted() { return attempted; }
        IOException getLastError() { return lastError; }
    }

    static final class Attempt {
        private final URI accepted;
        private final IOException error;

        Attempt(URI accepted, IOException error) {
            this.accepted = accepted;
            this.error = error;
        }

        URI getAccepted() { return accepted; }
        IOException getError() { return error; }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "vibem3u-hls-probe");
            thread.setDaemon(true);
            return thread;
        }
    }
}
