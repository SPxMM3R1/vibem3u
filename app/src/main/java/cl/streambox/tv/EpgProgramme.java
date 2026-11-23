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
        this(channelId, title, startMillis, stopMillis, normalizeDescription(description), true);
    }

    private EpgProgramme(
            String channelId,
            String title,
            long startMillis,
            long stopMillis,
            String normalizedDescription,
            boolean alreadyNormalized
    ) {
        this.channelId = channelId;
        this.title = title;
        this.startMillis = startMillis;
        this.stopMillis = stopMillis;
        this.description = normalizedDescription == null ? "" : normalizedDescription;
    }

    /**
     * Programa leído de la guía guardada (0.6.72): la sinopsis ya se normalizó al procesar el
     * XML, así que no se vuelve a recorrer letra por letra (en la TV eran segundos por arranque).
     */
    static EpgProgramme fromSnapshot(
            String channelId,
            String title,
            long startMillis,
            long stopMillis,
            String description
    ) {
        return new EpgProgramme(channelId, title, startMillis, stopMillis, description, true);
    }

    public String getChannelId() { return channelId; }
    public String getTitle() { return title; }
    public long getStartMillis() { return startMillis; }
    public long getStopMillis() { return stopMillis; }
    /** Plain synopsis from XMLTV {@code <desc>}; empty when the guide has none. */
    public String getDescription() { return description; }

    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    static String normalizeDescription(String value) {
        if (value == null || value.isEmpty()) return "";
        // 0.6.72: la guía guardada ya viene normalizada; sin espacios que colapsar se devuelve
        // la misma cadena (antes replaceAll compilaba la expresión por cada programa y abrir
        // la guía guardada costaba segundos en la TV).
        String collapsed = isNormalized(value)
                ? value
                : WHITESPACE.matcher(value).replaceAll(" ").trim();
        if (collapsed.length() <= MAX_DESCRIPTION_CHARS) return collapsed;
        return collapsed.substring(0, MAX_DESCRIPTION_CHARS - 1).trim() + "…";
    }

    private static boolean isNormalized(String value) {
        int length = value.length();
        if (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(length - 1))) {
            return false;
        }
        boolean previousSpace = false;
        for (int index = 0; index < length; index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current)) {
                if (current != ' ' || previousSpace) return false;
                previousSpace = true;
            } else {
                previousSpace = false;
            }
        }
        return true;
    }
}
