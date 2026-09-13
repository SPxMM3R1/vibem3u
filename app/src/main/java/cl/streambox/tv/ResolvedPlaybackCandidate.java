package cl.streambox.tv;

import android.os.SystemClock;

import java.util.Objects;

/**
 * A short-lived playback option exposed by a dynamic resolver.
 *
 * <p>The source contains the temporary URL in memory only. UI code should use
 * the safe label/detail fields and must not stringify the source URI.</p>
 */
final class ResolvedPlaybackCandidate {
    private final String label;
    private final String detail;
    private final ResolvedPlaybackSource source;
    private final long createdAtElapsedRealtime;

    ResolvedPlaybackCandidate(
            String label,
            String detail,
            ResolvedPlaybackSource source
    ) {
        this.label = AppStrings.isBlank(label) ? "Fuente" : label.trim();
        this.detail = SafePlaybackText.detail(detail);
        this.source = source;
        this.createdAtElapsedRealtime = SystemClock.elapsedRealtime();
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

    /**
     * Provider URLs are ephemeral, so prefer the stable logical identity and
     * only fall back to the URI when no identity was supplied.
     */
    boolean matches(ResolvedPlaybackSource activeSource) {
        if (source == null || activeSource == null) return false;
        String candidateOptionId = source.getPlaybackOptionId();
        String activeOptionId = activeSource.getPlaybackOptionId();
        if (!AppStrings.isBlank(candidateOptionId)
                && !AppStrings.isBlank(activeOptionId)) {
            return candidateOptionId.equals(activeOptionId);
        }
        return Objects.equals(source.getPlaybackUri(), activeSource.getPlaybackUri());
    }

    /** Candidate URLs are deliberately short-lived to avoid stale sessions. */
    boolean isStale() {
        return SystemClock.elapsedRealtime() - createdAtElapsedRealtime >= 20_000L;
    }
}
