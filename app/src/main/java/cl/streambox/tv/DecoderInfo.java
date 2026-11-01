package cl.streambox.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import java.util.Locale;

/**
 * Qué decodificador usa la señal (0.6.48, mockup aprobado): para «Información de la señal».
 * Guarda el nombre del decodificador de video y de audio en uso (Media3 avisa al crearlo y al
 * soltarlo) y lo traduce a «Hardware · MediaTek» / «Software · Android». El Dolby que va tal
 * cual a la tele no tiene decodificador en la app: «Directo a la TV».
 */
@UnstableApi
final class DecoderInfo implements AnalyticsListener {
    private volatile String videoDecoder = "";
    private volatile String audioDecoder = "";

    @Override
    public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
            long initializedTimestampMs, long initializationDurationMs) {
        videoDecoder = decoderName == null ? "" : decoderName;
    }

    @Override
    public void onVideoDecoderReleased(EventTime eventTime, String decoderName) {
        if (decoderName != null && decoderName.equals(videoDecoder)) videoDecoder = "";
    }

    @Override
    public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
            long initializedTimestampMs, long initializationDurationMs) {
        audioDecoder = decoderName == null ? "" : decoderName;
    }

    @Override
    public void onAudioDecoderReleased(EventTime eventTime, String decoderName) {
        if (decoderName != null && decoderName.equals(audioDecoder)) audioDecoder = "";
    }

    String videoDecoder() {
        return videoDecoder;
    }

    String audioDecoder() {
        return audioDecoder;
    }

    /** Dolby y similares que la app no decodifica: los decodifica la tele. */
    static boolean isPassthroughMime(String mime) {
        return MimeTypes.AUDIO_AC3.equals(mime) || MimeTypes.AUDIO_E_AC3.equals(mime)
                || MimeTypes.AUDIO_E_AC3_JOC.equals(mime) || MimeTypes.AUDIO_AC4.equals(mime)
                || MimeTypes.AUDIO_DTS.equals(mime) || MimeTypes.AUDIO_DTS_HD.equals(mime)
                || MimeTypes.AUDIO_TRUEHD.equals(mime);
    }

    /** Si el equipo informa ese decodificador como acelerado por hardware. */
    static boolean isHardware(String decoderName) {
        if (AppStrings.isBlank(decoderName)) return false;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder() && decoderName.equals(info.getName())) {
                    return info.isHardwareAccelerated();
                }
            }
        } catch (RuntimeException unavailable) {
            // Sin lista de códecs: se decide por el nombre.
        }
        return !looksSoftware(decoderName);
    }

    /** Decodificadores de software conocidos por su nombre (Android, FFmpeg, el MP2 de la app). */
    static boolean looksSoftware(String decoderName) {
        String name = decoderName.toLowerCase(Locale.ROOT);
        return name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.contains("ffmpeg") || name.contains("libgav1") || name.contains("mp2")
                || name.contains(".sw.") || name.endsWith(".sw");
    }

    /** Fabricante a partir del nombre del decodificador («OMX.MTK.VIDEO…» → «MediaTek»). */
    static String vendor(String decoderName) {
        if (AppStrings.isBlank(decoderName)) return "";
        String name = decoderName.toLowerCase(Locale.ROOT);
        String[] parts = name.split("\\.");
        String key = parts.length > 1 && (parts[0].equals("omx") || parts[0].equals("c2")) ? parts[1] : parts[0];
        switch (key) {
            case "mtk": case "mediatek": return "MediaTek";
            case "amlogic": case "aml": return "Amlogic";
            case "realtek": case "rtk": return "Realtek";
            case "qcom": case "qti": return "Qualcomm";
            case "exynos": case "sec": return "Samsung Exynos";
            case "hisi": case "hisilicon": return "HiSilicon";
            case "nvidia": return "NVIDIA";
            case "brcm": case "bcm": case "broadcom": return "Broadcom";
            case "ms": case "mstar": return "MStar";
            case "sprd": case "unisoc": return "Unisoc";
            case "rk": case "rockchip": return "Rockchip";
            case "allwinner": return "Allwinner";
            case "intel": return "Intel";
            case "dolby": return "Dolby";
            case "goldfish": return "Emulador";
            case "android": case "google": return "Android";
            default:
                if (looksSoftware(decoderName)) return "VibeM3U";
                return key.isEmpty() ? "" : key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
        }
    }

    /** «Hardware · MediaTek» + «\n» + nombre técnico; vacío si no hay decodificador. */
    static String label(String decoderName, boolean hardware, String hardwareWord, String softwareWord) {
        if (AppStrings.isBlank(decoderName)) return "";
        String kind = hardware ? hardwareWord : softwareWord;
        String vendor = vendor(decoderName);
        return (vendor.isEmpty() ? kind : kind + " · " + vendor) + "\n" + decoderName;
    }
}
