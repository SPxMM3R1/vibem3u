package cl.streambox.tv;

/** Immutable progress event for resolver diagnostics; it contains no URLs or credentials. */
public final class ResolutionProgress {
    private final ResolutionStage stage;
    private final int current;
    private final int total;

    private ResolutionProgress(ResolutionStage stage, int current, int total) {
        this.stage = stage;
        this.current = Math.max(0, current);
        this.total = Math.max(0, total);
    }

    public static ResolutionProgress of(ResolutionStage stage) {
        return new ResolutionProgress(stage, 0, 0);
    }

    public static ResolutionProgress counted(
            ResolutionStage stage,
            int current,
            int total
    ) {
        return new ResolutionProgress(stage, current, total);
    }

    public ResolutionStage getStage() {
        return stage;
    }

    public int getCurrent() {
        return current;
    }

    public int getTotal() {
        return total;
    }
}
