package cl.streambox.tv;

/**
 * Arma paquetes TS completos sin importar cómo llegan los bytes de la red (0.6.12), para que
 * {@link TsAudioPmtFilter} vea cada paquete entero antes de entregarlo.
 *
 * <p>Si el contenido no empieza con el byte de sincronía TS (listas M3U8, MP4…) o se pierde la
 * sincronía, el resto pasa sin tocar. Una lectura reanudada a mitad de segmento se alinea
 * suponiendo que el segmento empieza en un paquete, como en HLS; si no calza, también pasa
 * sin tocar.
 */
final class TsAudioStreamRewriter {
    private static final int PACKET = TsAudioPmtFilter.PACKET_SIZE;
    private static final int UNKNOWN = 0;
    private static final int TS = 1;
    private static final int PASS = 2;

    private final TsAudioPmtFilter filter;
    private final byte[] buffer = new byte[PACKET * 128];
    /** [start, ready): listo para entregar; [ready, end): recibido sin revisar. */
    private int start;
    private int ready;
    private int end;
    private int leadIn;
    private int mode = UNKNOWN;
    /** Bytes ya revisados del flujo (para ubicar una pérdida de sincronía). */
    private long consumed;
    /** Posición donde se perdió la sincronía TS; -1 si no ocurrió. */
    long syncLostAt = -1;

    TsAudioStreamRewriter(TsAudioPmtFilter filter, long startPosition) {
        this.filter = filter;
        long position = Math.max(0L, startPosition);
        leadIn = (int) ((PACKET - position % PACKET) % PACKET);
    }

    byte[] buffer() {
        return buffer;
    }

    /** Deja espacio para recibir y devuelve dónde escribir. */
    int prepareWrite() {
        if (start > 0 && (start == end || buffer.length - end < PACKET)) {
            System.arraycopy(buffer, start, buffer, 0, end - start);
            ready -= start;
            end -= start;
            start = 0;
        }
        return end;
    }

    int writableSpace() {
        return buffer.length - end;
    }

    /** Registra {@code count} bytes recibidos y revisa los paquetes completos. */
    void commit(int count, boolean endOfInput) {
        end += Math.max(0, count);
        process(endOfInput);
    }

    /**
     * El contenido empezó como TS válido (no es una lista ni otro formato), aunque después se
     * haya perdido la sincronía al final de un segmento todavía en escritura.
     */
    boolean isTransportStream() {
        return mode == TS || syncLostAt >= 0;
    }

    boolean hasReady() {
        return ready > start;
    }

    int drain(byte[] target, int offset, int length) {
        int count = Math.min(length, ready - start);
        System.arraycopy(buffer, start, target, offset, count);
        start += count;
        if (start == end) {
            start = 0;
            ready = 0;
            end = 0;
        }
        return count;
    }

    private void process(boolean endOfInput) {
        while (ready < end) {
            int pending = end - ready;
            if (leadIn > 0) {
                int count = Math.min(leadIn, pending);
                ready += count;
                leadIn -= count;
                continue;
            }
            if (mode == PASS) {
                ready = end;
                return;
            }
            if (pending < PACKET) {
                if (endOfInput) ready = end;
                return;
            }
            if ((buffer[ready] & 0xFF) != TsAudioPmtFilter.SYNC_BYTE) {
                if (mode == TS) syncLostAt = consumed;
                mode = PASS; // no es TS o perdió la sincronía: el resto, intacto
                continue;
            }
            mode = TS;
            filter.processPacket(buffer, ready);
            ready += PACKET;
            consumed += PACKET;
        }
    }
}
