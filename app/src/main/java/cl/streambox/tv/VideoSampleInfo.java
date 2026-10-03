package cl.streambox.tv;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Calidad real de un segmento de video (2026-10-03). Las listas HLS de TvVoo no declaran
 * resolución y el nombre de la versión engaña («HD» puede ser 576p), así que se lee el SPS
 * H.264 o HEVC del primer trozo del segmento que la validación ya descarga. Java puro: lo
 * usan la app, las pruebas JVM y local-catalog.
 */
final class VideoSampleInfo {
    final int width;
    final int height;
    /** Cuadros por segundo declarados en el SPS (H.264 con VUI); 0 si no se conocen. */
    final int fps;
    final String codec;

    VideoSampleInfo(int width, int height, int fps, String codec) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.codec = codec;
    }

    /** «1080p · 50 fps», «576p» o «» si no hay datos. */
    String label() {
        if (height <= 0) return "";
        String base = height + "p";
        return fps > 0 ? base + " · " + fps + " fps" : base;
    }

    /** Orden de calidad: más líneas primero y, a igual resolución, más cuadros. */
    long score() {
        return height <= 0 ? 0L : (long) height * 1000L + Math.max(0, fps);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%dx%d %s %dfps", width, height, codec, fps);
    }

    /** Analiza los primeros bytes de un segmento (MPEG-TS o Annex B); null si no hay SPS. */
    static VideoSampleInfo probe(byte[] data) {
        if (data == null || data.length < 8) return null;
        try {
            if (data[0] == 0x47 && (data.length < 189 || data[188] == 0x47)) {
                for (byte[] stream : transportPayloads(data).values()) {
                    VideoSampleInfo info = scan(stream);
                    if (info != null) return info;
                }
                return null;
            }
            return scan(data);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Une la carga útil de los paquetes TS por PID (PES incluido; sus cabeceras no son NAL válidas). */
    private static Map<Integer, byte[]> transportPayloads(byte[] data) {
        Map<Integer, ByteArrayOutputStream> streams = new LinkedHashMap<>();
        for (int offset = 0; offset + 188 <= data.length; offset += 188) {
            if (data[offset] != 0x47) break;
            int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
            int control = (data[offset + 3] >> 4) & 0x03;
            if (pid == 0 || pid == 0x1FFF || (control & 0x01) == 0) continue;
            int start = offset + 4;
            if ((control & 0x02) != 0) start += 1 + (data[offset + 4] & 0xFF);
            if (start >= offset + 188) continue;
            streams.computeIfAbsent(pid, ignored -> new ByteArrayOutputStream())
                    .write(data, start, offset + 188 - start);
        }
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, ByteArrayOutputStream> entry : streams.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return result;
    }

    private static VideoSampleInfo scan(byte[] data) {
        for (int index = 0; index + 4 < data.length; index++) {
            if (data[index] != 0 || data[index + 1] != 0 || data[index + 2] != 1) continue;
            int header = data[index + 3] & 0xFF;
            if ((header & 0x80) != 0) continue;
            int end = nextStartCode(data, index + 3);
            if ((header & 0x1F) == 7) {
                VideoSampleInfo info = parseH264(unescape(data, index + 4, end));
                if (info != null) return info;
            } else if (((header >> 1) & 0x3F) == 33 && index + 4 < data.length) {
                VideoSampleInfo info = parseH265(unescape(data, index + 5, end));
                if (info != null) return info;
            }
        }
        return null;
    }

    private static int nextStartCode(byte[] data, int from) {
        for (int index = from; index + 2 < data.length; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) return index;
        }
        return data.length;
    }

    /** Quita los bytes de prevención de emulación (00 00 03). */
    private static byte[] unescape(byte[] data, int start, int end) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, end - start));
        int zeros = 0;
        for (int index = start; index < end; index++) {
            int value = data[index] & 0xFF;
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    static VideoSampleInfo parseH264(byte[] sps) {
        Bits bits = new Bits(sps);
        int profile = bits.u(8);
        bits.skip(16);
        bits.ue();
        int chroma = 1;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128
                || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            chroma = bits.ue();
            if (chroma == 3) bits.skip(1);
            bits.ue();
            bits.ue();
            bits.skip(1);
            if (bits.u(1) == 1) {
                for (int list = 0; list < (chroma == 3 ? 12 : 8); list++) {
                    if (bits.u(1) == 1) skipScalingList(bits, list < 6 ? 16 : 64);
                }
            }
        }
        bits.ue();
        int pocType = bits.ue();
        if (pocType == 0) {
            bits.ue();
        } else if (pocType == 1) {
            bits.skip(1);
            bits.se();
            bits.se();
            int cycle = bits.ue();
            for (int index = 0; index < cycle; index++) bits.se();
        }
        bits.ue();
        bits.skip(1);
        int widthMbs = bits.ue() + 1;
        int heightUnits = bits.ue() + 1;
        int frameMbsOnly = bits.u(1);
        if (frameMbsOnly == 0) bits.skip(1);
        bits.skip(1);
        int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
        if (bits.u(1) == 1) {
            cropLeft = bits.ue();
            cropRight = bits.ue();
            cropTop = bits.ue();
            cropBottom = bits.ue();
        }
        int cropX = chroma == 0 ? 1 : (chroma == 3 ? 1 : 2);
        int cropY = (chroma == 0 ? 1 : (chroma == 1 ? 2 : 1)) * (2 - frameMbsOnly);
        int width = widthMbs * 16 - cropX * (cropLeft + cropRight);
        int height = (2 - frameMbsOnly) * heightUnits * 16 - cropY * (cropTop + cropBottom);
        int fps = 0;
        if (bits.remaining() > 0 && bits.u(1) == 1) {
            if (bits.u(1) == 1 && bits.u(8) == 255) bits.skip(32);
            if (bits.u(1) == 1) bits.skip(1);
            if (bits.u(1) == 1) {
                bits.skip(4);
                if (bits.u(1) == 1) bits.skip(24);
            }
            if (bits.u(1) == 1) {
                bits.ue();
                bits.ue();
            }
            if (bits.u(1) == 1) {
                long units = bits.u32();
                long scale = bits.u32();
                if (units > 0 && scale > 0) fps = (int) Math.round(scale / (2.0 * units));
            }
        }
        if (width < 16 || height < 16 || width > 8192 || height > 8192) return null;
        return new VideoSampleInfo(width, height, fps > 0 && fps <= 240 ? fps : 0, "h264");
    }

    static VideoSampleInfo parseH265(byte[] sps) {
        Bits bits = new Bits(sps);
        bits.skip(4);
        int maxSubLayersMinus1 = bits.u(3);
        bits.skip(1);
        bits.skip(96);
        boolean[] profilePresent = new boolean[maxSubLayersMinus1];
        boolean[] levelPresent = new boolean[maxSubLayersMinus1];
        for (int index = 0; index < maxSubLayersMinus1; index++) {
            profilePresent[index] = bits.u(1) == 1;
            levelPresent[index] = bits.u(1) == 1;
        }
        if (maxSubLayersMinus1 > 0) {
            for (int index = maxSubLayersMinus1; index < 8; index++) bits.skip(2);
        }
        for (int index = 0; index < maxSubLayersMinus1; index++) {
            if (profilePresent[index]) bits.skip(88);
            if (levelPresent[index]) bits.skip(8);
        }
        bits.ue();
        int chroma = bits.ue();
        if (chroma == 3) bits.skip(1);
        int width = bits.ue();
        int height = bits.ue();
        if (bits.u(1) == 1) {
            int subWidth = chroma == 1 || chroma == 2 ? 2 : 1;
            int subHeight = chroma == 1 ? 2 : 1;
            width -= subWidth * (bits.ue() + bits.ue());
            height -= subHeight * (bits.ue() + bits.ue());
        }
        if (width < 16 || height < 16 || width > 8192 || height > 8192) return null;
        return new VideoSampleInfo(width, height, 0, "hevc");
    }

    private static void skipScalingList(Bits bits, int size) {
        int last = 8;
        int next = 8;
        for (int index = 0; index < size; index++) {
            if (next != 0) next = (last + bits.se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }

    /** Lector de bits big-endian con Exp-Golomb; al pasar el final lanza RuntimeException. */
    private static final class Bits {
        private final byte[] data;
        private int position;

        Bits(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length * 8 - position;
        }

        int u(int count) {
            int value = 0;
            for (int index = 0; index < count; index++) {
                if (position >= data.length * 8) throw new IllegalStateException("SPS incompleto");
                int bit = (data[position >> 3] >> (7 - (position & 7))) & 1;
                value = (value << 1) | bit;
                position++;
            }
            return value;
        }

        long u32() {
            return ((long) u(16) << 16) | u(16);
        }

        void skip(int count) {
            if (position + count > data.length * 8) throw new IllegalStateException("SPS incompleto");
            position += count;
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                if (++zeros > 31) throw new IllegalStateException("Exp-Golomb inválido");
            }
            return zeros == 0 ? 0 : (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int value = ue();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
        }
    }
}
