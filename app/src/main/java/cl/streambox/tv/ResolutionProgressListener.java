package cl.streambox.tv;

/** Receives resolver stages with URLs already sanitized for diagnostics. */
@FunctionalInterface
public interface ResolutionProgressListener {
    ResolutionProgressListener NONE = progress -> { };

    void onProgress(ResolutionProgress progress);
}
