package cl.streambox.tv;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Idioma de audio en canales MPEG-TS con varias pistas (0.6.12).
 *
 * <p>Los canales directos traen en cada segmento el video y dos o más audios (español, inglés…)
 * intercalados en paquetes de 188 bytes; la tabla PMT dice qué PID es cada pista y en qué
 * idioma. En HLS, Media3 se queda con una sola pista de audio por tipo (la de PID menor), así que
 * no deja elegir. Este filtro edita solo la PMT: marca como tipo 0x00 (que Media3 ignora) los
 * audios que no son del idioma elegido y recalcula el CRC. Video, audio y el resto de los bytes
 * pasan intactos.
 *
 * <p>Por seguridad no toca nada si la sección no cabe completa en un paquete, si su CRC original
 * no es válido o si el idioma pedido no está: en esos casos el canal suena como siempre.
 * Una instancia por fuente; no es segura para hilos (cada lectura usa la suya).
 */
final class TsAudioPmtFilter {
    static final int PACKET_SIZE = 188;
    static final int SYNC_BYTE = 0x47;
    /** Tipo reservado: DefaultTsPayloadReaderFactory no crea lector y la pista se ignora. */
    static final int DISABLED_STREAM_TYPE = 0x00;

    /** Pista de audio anunciada en la PMT. */
    static final class AudioEntry {
        final int pid;
        final int streamType;
        /** Idioma normalizado («es», «en»…); vacío si la PMT no lo dice. */
        final String language;

        AudioEntry(int pid, int streamType, String language) {
            this.pid = pid;
            this.streamType = streamType;
            this.language = language;
        }
    }

    /** Recibe lo que se descubre y aplica; lo implementa la sesión del canal. */
    interface Listener {
        void onPmtPid(int pid);

        boolean isPmtPid(int pid);

        void onAudioEntries(List<AudioEntry> entries);

        void onLanguageApplied(String language);
    }

    private final String preferredLanguage;
    private final Listener listener;

    TsAudioPmtFilter(String preferredLanguage, Listener listener) {
        this.preferredLanguage = AudioTrackOptions.normalizeLanguage(preferredLanguage);
        this.listener = listener;
    }

    /** Revisa (y si corresponde edita) el paquete que empieza en {@code offset}. */
    void processPacket(byte[] data, int offset) {
        if ((data[offset] & 0xFF) != SYNC_BYTE) return;
        boolean payloadUnitStart = (data[offset + 1] & 0x40) != 0;
        if (!payloadUnitStart) return;
        int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
        boolean pat = pid == 0;
        if (!pat && !listener.isPmtPid(pid)) return;

        int packetEnd = offset + PACKET_SIZE;
        int adaptationControl = (data[offset + 3] >> 4) & 0x03;
        if (adaptationControl == 0 || adaptationControl == 2) return; // sin carga útil
        int position = offset + 4;
        if (adaptationControl == 3) position += 1 + (data[position] & 0xFF);
        if (position >= packetEnd) return;
        int sectionStart = position + 1 + (data[position] & 0xFF); // pointer_field
        if (sectionStart + 3 > packetEnd) return;
        int tableId = data[sectionStart] & 0xFF;
        if ((data[sectionStart + 1] & 0x80) == 0) return; // section_syntax_indicator
        int sectionLength = ((data[sectionStart + 1] & 0x0F) << 8) | (data[sectionStart + 2] & 0xFF);
        int sectionEnd = sectionStart + 3 + sectionLength;
        // Una sección partida en varios paquetes no se toca.
        if (sectionLength < 9 || sectionEnd > packetEnd) return;
        if (crc32(data, sectionStart, sectionEnd) != 0) return;

        if (pat && tableId == 0x00) {
            for (int entry = sectionStart + 8; entry + 4 <= sectionEnd - 4; entry += 4) {
                int programNumber = ((data[entry] & 0xFF) << 8) | (data[entry + 1] & 0xFF);
                int programPid = ((data[entry + 2] & 0x1F) << 8) | (data[entry + 3] & 0xFF);
                if (programNumber != 0) listener.onPmtPid(programPid);
            }
        } else if (!pat && tableId == 0x02) {
            processPmt(data, sectionStart, sectionEnd);
        }
    }

    private void processPmt(byte[] data, int sectionStart, int sectionEnd) {
        if (sectionStart + 12 > sectionEnd - 4) return;
        int programInfoLength = ((data[sectionStart + 10] & 0x0F) << 8)
                | (data[sectionStart + 11] & 0xFF);
        int position = sectionStart + 12 + programInfoLength;
        int limit = sectionEnd - 4;
        List<AudioEntry> audio = new ArrayList<>();
        List<Integer> streamTypePositions = new ArrayList<>();
        while (position + 5 <= limit) {
            int streamType = data[position] & 0xFF;
            int pid = ((data[position + 1] & 0x1F) << 8) | (data[position + 2] & 0xFF);
            int infoLength = ((data[position + 3] & 0x0F) << 8) | (data[position + 4] & 0xFF);
            int descriptorsStart = position + 5;
            int descriptorsEnd = descriptorsStart + infoLength;
            if (descriptorsEnd > limit) return; // PMT mal formada: no se toca
            int effectiveType = streamType;
            String language = "";
            for (int d = descriptorsStart; d + 2 <= descriptorsEnd; ) {
                int tag = data[d] & 0xFF;
                int length = data[d + 1] & 0xFF;
                int body = d + 2;
                if (body + length > descriptorsEnd) break;
                if (tag == 0x0A && length >= 3) {
                    language = new String(data, body, 3, StandardCharsets.ISO_8859_1).trim();
                } else if (streamType == 0x06 || streamType == 0x05) {
                    effectiveType = typeFromDescriptor(tag, data, body, length, effectiveType);
                }
                d = body + length;
            }
            if ((streamType == 0x06 || streamType == 0x05) && effectiveType == streamType) {
                effectiveType = -1; // privado sin descriptor conocido: Media3 tampoco lo lee
            }
            if (isAudioStreamType(effectiveType)) {
                audio.add(new AudioEntry(pid, effectiveType,
                        AudioTrackOptions.normalizeLanguage(language)));
                streamTypePositions.add(position);
            }
            position = descriptorsEnd;
        }
        if (audio.isEmpty()) return;
        listener.onAudioEntries(audio);
        if (preferredLanguage.isEmpty() || audio.size() < 2) return;
        boolean present = false;
        for (AudioEntry entry : audio) present |= entry.language.equals(preferredLanguage);
        if (!present) return;
        boolean changed = false;
        for (int index = 0; index < audio.size(); index++) {
            if (audio.get(index).language.equals(preferredLanguage)) continue;
            data[streamTypePositions.get(index)] = (byte) DISABLED_STREAM_TYPE;
            changed = true;
        }
        if (!changed) return;
        int crc = crc32(data, sectionStart, sectionEnd - 4);
        data[sectionEnd - 4] = (byte) (crc >>> 24);
        data[sectionEnd - 3] = (byte) (crc >>> 16);
        data[sectionEnd - 2] = (byte) (crc >>> 8);
        data[sectionEnd - 1] = (byte) crc;
        listener.onLanguageApplied(preferredLanguage);
    }

    /** Igual que Media3 (TsExtractor.readEsInfo) para los tipos privados 0x05/0x06. */
    private static int typeFromDescriptor(int tag, byte[] data, int body, int length, int current) {
        switch (tag) {
            case 0x05: // registration_descriptor
                if (length >= 4) {
                    String id = new String(data, body, 4, StandardCharsets.ISO_8859_1);
                    if (id.equals("AC-3")) return 0x81;
                    if (id.equals("EAC3")) return 0x87;
                    if (id.equals("AC-4")) return 0xAC;
                }
                return current;
            case 0x6A: return 0x81; // AC-3 (DVB)
            case 0x7A: return 0x87; // E-AC-3 (DVB)
            case 0x7B: return 0x8A; // DTS
            case 0x7F: // extensión DVB
                if (length >= 1) {
                    int extension = data[body] & 0xFF;
                    if (extension == 0x15) return 0xAC;
                    if (extension == 0x0E) return 0x88;
                    if (extension == 0x21) return 0x8B;
                }
                return current;
            default:
                return current;
        }
    }

    static boolean isAudioStreamType(int streamType) {
        switch (streamType) {
            case 0x03: case 0x04: // MPEG audio (MP2/MP3)
            case 0x0F: case 0x11: // AAC ADTS / LATM
            case 0x81: case 0x87: // AC-3 / E-AC-3
            case 0xAC: // AC-4
            case 0x82: case 0x8A: case 0x88: case 0x8B: // DTS
            case 0x2D: // MPEG-H
                return true;
            default:
                return false;
        }
    }

    /** CRC-32/MPEG-2 (polinomio 0x04C11DB7, sin reflejar). Sobre sección + CRC da 0. */
    static int crc32(byte[] data, int start, int end) {
        int crc = 0xFFFFFFFF;
        for (int index = start; index < end; index++) {
            crc ^= (data[index] & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }
}
