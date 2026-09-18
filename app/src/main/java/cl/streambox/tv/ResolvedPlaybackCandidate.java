package cl.streambox.tv;

/**
 * A short-lived playback option exposed by a dynamic resolver.
 *
 * <p>The source contains the temporary URL in memory only. UI code should use
 * the safe label/detail fields and must not stringify the source URI.</p>
 */
final class ResolvedPlaybackCandidate {
    private static final long STALE_AFTER_NANOS = 20_000_000_000L;
    private final String label;
    private final String detail;
    private final ResolvedPlaybackSource source;
    private final long createdAtNanos;

    ResolvedPlaybackCandidate(
            String label,
            String detail,
            ResolvedPlaybackSource source
    ) {
        this.label = AppStrings.isBlank(label) ? "Fuente" : label.trim();
        this.detail = SafePlaybackText.detail(detail);
        this.source = source;
        this.createdAtNanos = System.nanoTime();
    }

    String getLabel() {
        return label;
    }

    String getDetail() {
        return detail;
    }

    ResolvedPlaybackSource getSource() {
        return source;
    }

    /** Candidate URLs are deliberately short-lived to avoid stale sessions. */
    boolean isStale() {
        return System.nanoTime() - createdAtNanos >= STALE_AFTER_NANOS;
    }
}
