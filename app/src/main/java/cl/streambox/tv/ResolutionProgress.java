package cl.streambox.tv;

/** Immutable progress event for safe, detailed resolver diagnostics. */
public final class ResolutionProgress {
    private final ResolutionStage stage;
    private final int current;
    private final int total;
    private final String detail;

    private ResolutionProgress(ResolutionStage stage, int current, int total, String detail) {
        this.stage = stage;
        this.current = Math.max(0, current);
        this.total = Math.max(0, total);
        this.detail = SafePlaybackText.detail(detail);
    }

    public static ResolutionProgress of(ResolutionStage stage) {
        return new ResolutionProgress(stage, 0, 0, "");
    }

    public static ResolutionProgress of(ResolutionStage stage, String detail) {
        return new ResolutionProgress(stage, 0, 0, detail);
    }

    public static ResolutionProgress counted(
            ResolutionStage stage,
            int current,
            int total
    ) {
        return new ResolutionProgress(stage, current, total, "");
    }

    public static ResolutionProgress counted(
            ResolutionStage stage,
            int current,
            int total,
            String detail
    ) {
        return new ResolutionProgress(stage, current, total, detail);
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

    public String getDetail() {
        return detail;
    }
}
