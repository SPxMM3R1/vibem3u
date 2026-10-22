package cl.streambox.tv;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * Sincronía del audio por canal (0.6.29, mockup aprobado). El audio es el reloj del reproductor:
 * el video muestra cada cuadro cuando el reloj llega a su tiempo. Para correr el audio respecto
 * del video no se toca el sonido; se informa la posición del audio desplazada y el video se
 * acomoda solo. Positivo = el audio suena después (se usa si la voz llega antes que los labios).
 *
 * <p>El valor vive aquí (estático) para que Opciones lo cambie en vivo mientras el canal suena;
 * lo guardado por canal está en {@link PlaybackPreferences}.
 */
@UnstableApi
final class AudioSyncOffset {
    static final int STEP_MS = 20;
    static final int LIMIT_MS = 500;

    private static volatile long offsetUs;

    private AudioSyncOffset() {}

    static int clampMs(int value) {
        return Math.max(-LIMIT_MS, Math.min(LIMIT_MS, value));
    }

    static void setMs(int value) {
        offsetUs = clampMs(value) * 1000L;
    }

    static int getMs() {
        return (int) (offsetUs / 1000L);
    }

    /** Texto del valor: «0 ms», «+60 ms», «−60 ms». */
    static String label(int valueMs) {
        if (valueMs == 0) return "0 ms";
        return (valueMs > 0 ? "+" : "−") + Math.abs(valueMs) + " ms";
    }

    /** Envuelve el sink de audio del reproductor y desplaza la posición que informa. */
    static final class Sink extends ForwardingAudioSink {
        Sink(AudioSink sink) {
            super(sink);
        }

        @Override
        public long getCurrentPositionUs(boolean sourceEnded) {
            long position = super.getCurrentPositionUs(sourceEnded);
            if (position == CURRENT_POSITION_NOT_SET) return position;
            // Reloj adelantado = el video avanza antes = el audio queda atrás (y al revés).
            return Math.max(0L, position + offsetUs);
        }
    }
}
