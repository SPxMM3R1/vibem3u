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
    /** Versión del proveedor (TvVoo: alias); identifica la fila aunque la URL cambie. */
    private final String variantId;
    /** Calidad real medida («1080p · 50 fps»), o vacío. */
    private final String quality;
    /** false: la versión no respondió y la fila solo informa. */
    private final boolean available;
    /** Es la versión elegida en el editor. */
    private final boolean preferred;
    /** Líneas del video medidas (1080, 720…); 0 si no se sabe. */
    private final int qualityHeight;

    ResolvedPlaybackCandidate(
            String label,
            String detail,
            ResolvedPlaybackSource source
    ) {
        this.label = AppStrings.isBlank(label) ? "Fuente" : label.trim();
        this.detail = SafePlaybackText.detail(detail);
        this.source = source;
        this.createdAtNanos = System.nanoTime();
        this.variantId = source == null || source.getVariantId() == null ? "" : source.getVariantId();
        this.quality = "";
        this.available = source != null;
        this.preferred = false;
        this.qualityHeight = 0;
    }

    private ResolvedPlaybackCandidate(
            String label,
            String detail,
            ResolvedPlaybackSource source,
            String variantId,
            String quality,
            boolean available,
            boolean preferred,
            int qualityHeight
    ) {
        this.label = AppStrings.isBlank(label) ? "Fuente" : label.trim();
        this.detail = SafePlaybackText.detail(detail);
        this.source = source;
        this.createdAtNanos = System.nanoTime();
        this.variantId = variantId == null ? "" : variantId;
        this.quality = quality == null ? "" : quality;
        this.available = available && source != null;
        this.preferred = preferred;
        this.qualityHeight = Math.max(0, qualityHeight);
    }

    /** Fila de versión (TvVoo): nombre, detalle, calidad medida y si se puede elegir. */
    static ResolvedPlaybackCandidate version(
            String label,
            String detail,
            ResolvedPlaybackSource source,
            String variantId,
            String quality,
            boolean available,
            boolean preferred,
            int qualityHeight
    ) {
        return new ResolvedPlaybackCandidate(label, detail, source, variantId, quality, available,
                preferred, qualityHeight);
    }

    int getQualityHeight() {
        return qualityHeight;
    }

    String getVariantId() {
        return variantId;
    }

    String getQuality() {
        return quality;
    }

    boolean isAvailable() {
        return available;
    }

    boolean isPreferred() {
        return preferred;
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
