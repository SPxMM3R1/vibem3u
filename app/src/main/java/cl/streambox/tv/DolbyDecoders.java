package cl.streambox.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

/**
 * ¿Puede este equipo decodificar Dolby (AC3 y E-AC3) dentro de la app? (0.6.38). Solo entonces
 * la nivelación de volumen deja de mandar el Dolby directo a la tele; si falta alguno, el audio
 * Dolby seguiría sin poder sonar, así que se mantiene el paso directo.
 */
final class DolbyDecoders {
    private static final String TAG = "VibeM3U-Audio";
    private static volatile Boolean available;

    private DolbyDecoders() {}

    static boolean available() {
        Boolean cached = available;
        if (cached != null) return cached;
        boolean result;
        try {
            result = hasDecoder("audio/ac3") && hasDecoder("audio/eac3");
        } catch (RuntimeException unavailable) {
            result = false;
        }
        Log.i(TAG, "decodificadores Dolby en la app: " + (result ? "sí" : "no"));
        available = result;
        return result;
    }

    private static boolean hasDecoder(String mimeType) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimeType)) return true;
            }
        }
        return false;
    }
}
