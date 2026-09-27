package cl.streambox.tv;

/** Channel-specific quality defaults for streams that benefit from adaptation. */
final class PlaybackQualityPolicy {
    private static final String ARIRANG_TVG_ID = "ArirangTV.kr";

    private PlaybackQualityPolicy() {}

    static boolean shouldUseAutomaticQuality(
            Channel channel,
            PlaybackPreferences.QualityPreference savedQuality,
            boolean explicitlyAutomatic
    ) {
        return explicitlyAutomatic
                || (savedQuality == null && defaultsToAutomatic(channel));
    }

    private static boolean defaultsToAutomatic(Channel channel) {
        return channel != null && ARIRANG_TVG_ID.equalsIgnoreCase(channel.getTvgId());
    }
}
