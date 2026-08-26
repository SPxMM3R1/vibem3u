package cl.streambox.tv;

/** Receives safe resolver stages without exposing provider URLs or credentials. */
@FunctionalInterface
public interface ResolutionProgressListener {
    ResolutionProgressListener NONE = progress -> { };

    void onProgress(ResolutionProgress progress);
}
