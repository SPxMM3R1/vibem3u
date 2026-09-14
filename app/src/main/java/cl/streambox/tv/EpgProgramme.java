package cl.streambox.tv;

public final class EpgProgramme {
    private final String channelId;
    private final String title;
    private final String description;
    private final long startMillis;
    private final long stopMillis;

    public EpgProgramme(String channelId, String title, long startMillis, long stopMillis) {
        this(channelId, title, "", startMillis, stopMillis);
    }

    public EpgProgramme(
            String channelId,
            String title,
            String description,
            long startMillis,
            long stopMillis
    ) {
        this.channelId = channelId;
        this.title = title;
        this.description = description == null ? "" : description;
        this.startMillis = startMillis;
        this.stopMillis = stopMillis;
    }

    public String getChannelId() { return channelId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public long getStartMillis() { return startMillis; }
    public long getStopMillis() { return stopMillis; }
}
