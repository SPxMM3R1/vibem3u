package cl.streambox.tv;

public final class EpgProgramme {
    /** Longest synopsis kept per programme; the detail panel shows at most three lines. */
    static final int MAX_DESCRIPTION_CHARS = 600;

    private final String channelId;
    private final String title;
    private final long startMillis;
    private final long stopMillis;
    private final String description;

    public EpgProgramme(String channelId, String title, long startMillis, long stopMillis) {
        this(channelId, title, startMillis, stopMillis, "");
    }

    public EpgProgramme(
            String channelId,
            String title,
            long startMillis,
            long stopMillis,
            String description
    ) {
        this.channelId = channelId;
        this.title = title;
        this.startMillis = startMillis;
        this.stopMillis = stopMillis;
        this.description = normalizeDescription(description);
    }

    public String getChannelId() { return channelId; }
    public String getTitle() { return title; }
    public long getStartMillis() { return startMillis; }
    public long getStopMillis() { return stopMillis; }
    /** Plain synopsis from XMLTV {@code <desc>}; empty when the guide has none. */
    public String getDescription() { return description; }

    static String normalizeDescription(String value) {
        if (value == null) return "";
        String collapsed = value.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= MAX_DESCRIPTION_CHARS) return collapsed;
        return collapsed.substring(0, MAX_DESCRIPTION_CHARS - 1).trim() + "…";
    }
}
