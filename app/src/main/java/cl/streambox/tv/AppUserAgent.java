package cl.streambox.tv;

/**
 * User-Agent de la app para reproductor, logos, guía y listas (0.5.88; antes cada clase
 * fijaba «VibeM3U/0.4.42»). Vive aparte de {@link SharedHttpClient} porque ese archivo
 * también lo compila el auxiliar local, que no tiene {@code BuildConfig}.
 */
final class AppUserAgent {
    static final String VALUE = "VibeM3U/" + BuildConfig.VERSION_NAME + " (Android TV)";

    private AppUserAgent() {}
}
