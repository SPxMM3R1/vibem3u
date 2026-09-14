package cl.streambox.tv;

public final class EpgProgramme {
    private final String channelId;
    private final String title;
    private final long startMillis;
    private final long stopMillis;

    public EpgProgramme(String channelId, String title, long startMillis, long stopMillis) {
        this.channelId = channelId;
        this.title = title;
        this.startMillis = startMillis;
        this.stopMillis = stopMillis;
    }

    public String getChannelId() { return channelId; }
    public String getTitle() { return title; }
    public long getStartMillis() { return startMillis; }
    public long getStopMillis() { return stopMillis; }
}
