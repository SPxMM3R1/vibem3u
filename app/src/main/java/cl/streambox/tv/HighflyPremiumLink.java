package cl.streambox.tv;

/**
 * Puente sin Android entre el resolutor de Highfly y el token guardado. La app instala
 * el almacén cifrado; las pruebas JVM y local-catalog no instalan nada y Premium queda
 * apagado.
 */
final class HighflyPremiumLink {
    interface Source {
        /** Hay token guardado. */
        boolean linked();

        /** Highfly ya rechazó el token guardado (vencido o revocado). */
        boolean rejected();

        /** Copia breve del token para una solicitud, o null. */
        String token();

        HighflyPremiumRegion region();

        void markRejected();
    }

    private static volatile Source source;

    private HighflyPremiumLink() {}

    static void install(Source value) {
        source = value;
    }

    static Source current() {
        return source;
    }
}
