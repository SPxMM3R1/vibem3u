package cl.streambox.tv;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Bounded, process-only opening measurements. No channel names, URLs, or credentials. */
final class PlaybackStartupMetrics {
    enum Reason { CHANNEL, RETRY, REFRESH, FALLBACK }
    private static final int MAX_HISTORY = 128;
    private final LongSupplier nanoTime;
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private long sequence;
    private Attempt active;

    PlaybackStartupMetrics() { this(System::nanoTime); }
    PlaybackStartupMetrics(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    synchronized long begin(String provider, Reason reason) {
        finishCurrent();
        String safeProvider = provider != null && provider.matches("[a-zA-Z0-9_-]{1,32}")
                ? provider.toLowerCase(Locale.ROOT) : "direct";
        active = new Attempt(++sequence, safeProvider, reason, nanoTime.getAsLong());
        return sequence;
    }

    synchronized long currentId() { return active == null ? -1L : active.id; }
    synchronized void dequeued(long id) { if (matches(id)) active.dequeued = elapsed(); }
    synchronized void resolved(long id) { if (matches(id)) active.resolved = elapsed(); }
    synchronized void stage(long id, ResolutionStage stage) {
        if (!matches(id)) return;
        if (stage == ResolutionStage.CACHE_REUSED) active.cacheReused = true;
    }
    synchronized void manifest(long id) {
        if (matches(id) && active.manifest < 0L) active.manifest = elapsed();
    }
    synchronized void segment(long id) {
        if (matches(id) && active.segment < 0L) active.segment = elapsed();
    }
    synchronized boolean firstFrame(long id) {
        if (!matches(id) || active.firstFrame >= 0L) return false;
        active.firstFrame = elapsed();
        active.outcome = "playing";
        return true;
    }
    synchronized void buffering(long id, boolean buffering) {
        if (!matches(id) || active.firstFrame < 0L) return;
        if (buffering && active.bufferingSince < 0L) {
            active.bufferingSince = elapsed();
            active.rebuffers++;
        } else if (!buffering && active.bufferingSince >= 0L) {
            active.rebufferMs += elapsed() - active.bufferingSince;
            active.bufferingSince = -1L;
        }
    }
    synchronized void failed(long id) { if (matches(id)) active.outcome = "failed"; }
    synchronized Sample snapshot() { return active == null ? null : sample(); }
    synchronized void finish() { finishCurrent(); active = null; }
    synchronized List<Sample> samples() {
        List<Sample> result = new ArrayList<>(history);
        if (active != null) result.add(sample());
        return Collections.unmodifiableList(result);
    }

    synchronized String summary(String provider) {
        List<Long> openings = new ArrayList<>();
        int failures = 0;
        int cancelled = 0;
        for (Sample sample : samples()) {
            if (!sample.provider.equals(provider)) continue;
            if (sample.firstFrameMs >= 0) openings.add(sample.firstFrameMs);
            if ("failed".equals(sample.outcome)) failures++;
            if ("cancelled".equals(sample.outcome)) cancelled++;
        }
        Collections.sort(openings);
        return "provider=" + provider + " opened=" + openings.size() + " failed=" + failures
                + " cancelled=" + cancelled + " p50Ms=" + percentile(openings, .50)
                + " p95Ms=" + percentile(openings, .95);
    }

    private static long percentile(List<Long> values, double percentile) {
        return values.isEmpty() ? -1L : values.get(Math.max(0,
                (int) Math.ceil(values.size() * percentile) - 1));
    }
    private boolean matches(long id) { return active != null && active.id == id; }
    private long elapsed() { return Math.max(0L, (nanoTime.getAsLong() - active.started) / 1_000_000L); }
    private Sample sample() {
        long buffer = active.rebufferMs + (active.bufferingSince < 0 ? 0 : elapsed() - active.bufferingSince);
        return new Sample(active, buffer);
    }
    private void finishCurrent() {
        if (active == null) return;
        if ("opening".equals(active.outcome)) active.outcome = "cancelled";
        history.addLast(sample());
        while (history.size() > MAX_HISTORY) history.removeFirst();
    }

    static final class Sample {
        final long id, queueMs, resolvedMs, manifestMs, segmentMs, firstFrameMs, rebufferMs;
        final String provider, outcome;
        final Reason reason;
        final boolean cacheReused;
        final int rebuffers;
        Sample(Attempt value, long rebufferMs) {
            id = value.id; provider = value.provider; reason = value.reason; outcome = value.outcome;
            queueMs = value.dequeued; resolvedMs = value.resolved; manifestMs = value.manifest;
            segmentMs = value.segment; firstFrameMs = value.firstFrame;
            cacheReused = value.cacheReused; rebuffers = value.rebuffers; this.rebufferMs = rebufferMs;
        }
        String safeSummary() {
            return "attempt=" + id + " provider=" + provider + " reason=" + reason
                    + " outcome=" + outcome + " queueMs=" + queueMs + " resolvedMs=" + resolvedMs
                    + " manifestMs=" + manifestMs + " segmentMs=" + segmentMs + " firstFrameMs="
                    + firstFrameMs + " cache=" + cacheReused + " rebuffers=" + rebuffers
                    + " rebufferMs=" + rebufferMs;
        }
    }
    private static final class Attempt {
        final long id, started;
        final String provider;
        final Reason reason;
        long dequeued = -1, resolved = -1, manifest = -1, segment = -1, firstFrame = -1;
        long bufferingSince = -1, rebufferMs;
        int rebuffers;
        boolean cacheReused;
        String outcome = "opening";
        Attempt(long id, String provider, Reason reason, long started) {
            this.id = id; this.provider = provider; this.reason = reason; this.started = started;
        }
    }
}
