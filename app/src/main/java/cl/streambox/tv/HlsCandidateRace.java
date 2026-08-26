package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
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

        List<URI> unique = new ArrayList<>();
        Set<URI> seen = new LinkedHashSet<>();
        int limit = Math.max(1, maximumCandidates);
        for (URI candidate : candidates) {
            if (candidate != null && seen.add(candidate)) {
                unique.add(candidate);
                if (unique.size() >= limit) break;
            }
        }
        if (unique.isEmpty()) return new Result(null, 0, null);

        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        int workerCount = Math.max(1, Math.min(Math.max(1, parallelism), unique.size()));
        ExecutorService executor = Executors.newFixedThreadPool(
                workerCount,
                new DaemonThreadFactory()
        );
        CompletionService<Attempt> completion = new ExecutorCompletionService<>(executor);
        List<Future<Attempt>> submitted = new ArrayList<>();
        IOException lastError = null;
        int nextIndex = 0;
        int completed = 0;

        try {
            while (completed < unique.size()) {
                while (nextIndex < unique.size()
                        && submitted.size() - completed < workerCount) {
                    deadline.check();
                    URI candidate = unique.get(nextIndex);
                    int displayIndex = progressOffset + nextIndex + 1;
                    int displayTotal = progressTotal > 0
                            ? progressTotal
                            : unique.size() + progressOffset;
                    progress.onProgress(ResolutionProgress.counted(
                            ResolutionStage.SOURCE_CANDIDATE,
                            displayIndex,
                            displayTotal
                    ));
                    submitted.add(completion.submit(new ValidationTask(candidate, validator)));
                    nextIndex++;
                }

                deadline.check();
                Future<Attempt> finished;
                try {
                    finished = completion.poll(
                            deadline.remainingMillis(),
                            TimeUnit.MILLISECONDS
                    );
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", error);
                }
                if (finished == null) {
                    throw new IOException("Tiempo de resolución agotado.");
                }
                completed++;
                try {
                    Attempt attempt = finished.get();
                    if (attempt.error == null && attempt.accepted != null) {
                        return new Result(attempt.accepted, nextIndex, lastError);
                    }
                    if (attempt.error != null) lastError = attempt.error;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", error);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    lastError = cause instanceof IOException
                            ? (IOException) cause
                            : new IOException("No se pudo validar la fuente.", cause);
                }
            }
        } finally {
            for (Future<Attempt> future : submitted) {
                if (!future.isDone()) future.cancel(true);
            }
            executor.shutdownNow();
            try {
                executor.awaitTermination(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        return new Result(null, nextIndex, lastError);
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

    private static final class ValidationTask implements Callable<Attempt> {
        private final URI candidate;
        private final CandidateValidator validator;

        ValidationTask(URI candidate, CandidateValidator validator) {
            this.candidate = candidate;
            this.validator = validator;
        }

        @Override
        public Attempt call() {
            try {
                return new Attempt(validator.validate(candidate), null);
            } catch (IOException error) {
                return new Attempt(null, error);
            }
        }
    }

    private static final class Attempt {
        private final URI accepted;
        private final IOException error;

        Attempt(URI accepted, IOException error) {
            this.accepted = accepted;
            this.error = error;
        }
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
