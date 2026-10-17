package cl.streambox.tv;

import java.util.Arrays;

/**
 * Decodificador MP2 (MPEG-1/2 Audio Layer II) en Java puro (0.6.06).
 *
 * <p>Muchos canales directos (ESPN, AMC, Cinecanal, CNN Chile…) traen el audio en MP2, como
 * sale del satélite. Los televisores con chip MediaTek lo decodifican, pero Android puro no trae
 * decodificador MP2 (Chromecast con Google TV, el emulador): Media3 descarta la pista y el canal
 * se ve sin sonido. Este decodificador cubre ese caso.
 *
 * <p>Es un port de la parte Layer II y del banco de síntesis de minimp3
 * (github.com/lieff/minimp3, dominio público CC0). Cada cuadro entrega 1152 muestras PCM de
 * 16 bits por canal, intercaladas. No es seguro para hilos: una instancia por decodificador.
 */
final class Mp2FrameDecoder {
    static final int SAMPLES_PER_FRAME = 1152;
    static final int HEADER_SIZE = 4;
    /** Cuadro más grande posible: 384 kbps a 32 kHz, con relleno. */
    static final int MAX_FRAME_BYTES = 1729;

    private static final int MODE_JOINT_STEREO = 1;
    private static final int MODE_MONO = 3;

    private static final int[] SAMPLE_RATES = {44100, 48000, 32000};
    private static final int[] BITRATES_MPEG1 =
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
    private static final int[] BITRATES_MPEG2 =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /** Cabecera de un cuadro Layer II válido. */
    static final class Header {
        final boolean mpeg1;
        final int sampleRateIndex;
        final int sampleRate;
        final int bitrateKbps;
        final int mode;
        final int modeExtension;
        final boolean crc;
        final int channels;
        final int frameBytes;

        Header(boolean mpeg1, int sampleRateIndex, int sampleRate, int bitrateKbps, int mode,
                int modeExtension, boolean crc, int frameBytes) {
            this.mpeg1 = mpeg1;
            this.sampleRateIndex = sampleRateIndex;
            this.sampleRate = sampleRate;
            this.bitrateKbps = bitrateKbps;
            this.mode = mode;
            this.modeExtension = modeExtension;
            this.crc = crc;
            this.channels = mode == MODE_MONO ? 1 : 2;
            this.frameBytes = frameBytes;
        }
    }

    /** Lee la cabecera en {@code offset}; null si no empieza ahí un cuadro Layer II válido. */
    static Header parseHeader(byte[] data, int offset, int limit) {
        if (offset < 0 || limit - offset < HEADER_SIZE) return null;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;
        if ((data[offset] & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) return null;
        int version = (b1 >> 3) & 3;
        if (version == 1 || ((b1 >> 1) & 3) != 2) return null;
        int bitrateIndex = b2 >> 4;
        int sampleRateIndex = (b2 >> 2) & 3;
        // Formato libre (índice 0) no se usa en televisión; 15 y 3 son inválidos.
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null;
        boolean mpeg1 = version == 3;
        int sampleRate = SAMPLE_RATES[sampleRateIndex] >> (mpeg1 ? 0 : 1) >> (version == 0 ? 1 : 0);
        int kbps = (mpeg1 ? BITRATES_MPEG1 : BITRATES_MPEG2)[bitrateIndex];
        int frameBytes = 144_000 * kbps / sampleRate + ((b2 >> 1) & 1);
        return new Header(mpeg1, sampleRateIndex, sampleRate, kbps, b3 >> 6, (b3 >> 4) & 3,
                (b1 & 1) == 0, frameBytes);
    }

    // ---------------------------------------------------------------------------------
    // Tablas (minimp3)
    // ---------------------------------------------------------------------------------

    /** Código de asignación → bits de la muestra (1..16) o 17/18/19 = agrupada en 3/5/9 niveles. */
    private static final int[] BITALLOC_CODE_TAB = {
        0, 17, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16,
        0, 17, 18, 3, 19, 4, 5, 16,
        0, 17, 18, 16,
        0, 17, 18, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    /** Tramos de subbandas: {desplazamiento en la tabla de códigos, bits del código, subbandas}. */
    private static final int[][] ALLOC_MPEG2 = {{60, 4, 4}, {44, 3, 7}, {44, 2, 19}};
    private static final int[][] ALLOC_MPEG1 = {{0, 4, 3}, {16, 4, 8}, {32, 3, 12}, {40, 2, 7}};
    private static final int[][] ALLOC_MPEG1_LOW_RATE = {{44, 4, 2}, {44, 3, 10}};

    private static final float[] DEQUANT = {
        9.53674316e-07f / 3, 7.56931807e-07f / 3, 6.00777173e-07f / 3,
        9.53674316e-07f / 7, 7.56931807e-07f / 7, 6.00777173e-07f / 7,
        9.53674316e-07f / 15, 7.56931807e-07f / 15, 6.00777173e-07f / 15,
        9.53674316e-07f / 31, 7.56931807e-07f / 31, 6.00777173e-07f / 31,
        9.53674316e-07f / 63, 7.56931807e-07f / 63, 6.00777173e-07f / 63,
        9.53674316e-07f / 127, 7.56931807e-07f / 127, 6.00777173e-07f / 127,
        9.53674316e-07f / 255, 7.56931807e-07f / 255, 6.00777173e-07f / 255,
        9.53674316e-07f / 511, 7.56931807e-07f / 511, 6.00777173e-07f / 511,
        9.53674316e-07f / 1023, 7.56931807e-07f / 1023, 6.00777173e-07f / 1023,
        9.53674316e-07f / 2047, 7.56931807e-07f / 2047, 6.00777173e-07f / 2047,
        9.53674316e-07f / 4095, 7.56931807e-07f / 4095, 6.00777173e-07f / 4095,
        9.53674316e-07f / 8191, 7.56931807e-07f / 8191, 6.00777173e-07f / 8191,
        9.53674316e-07f / 16383, 7.56931807e-07f / 16383, 6.00777173e-07f / 16383,
        9.53674316e-07f / 32767, 7.56931807e-07f / 32767, 6.00777173e-07f / 32767,
        9.53674316e-07f / 65535, 7.56931807e-07f / 65535, 6.00777173e-07f / 65535,
        9.53674316e-07f / 3, 7.56931807e-07f / 3, 6.00777173e-07f / 3,
        9.53674316e-07f / 5, 7.56931807e-07f / 5, 6.00777173e-07f / 5,
        9.53674316e-07f / 9, 7.56931807e-07f / 9, 6.00777173e-07f / 9
    };

    private static final float[] DCT_SECANTS = {
        10.19000816f, 0.50060302f, 0.50241929f, 3.40760851f, 0.50547093f, 0.52249861f,
        2.05778098f, 0.51544732f, 0.56694406f, 1.48416460f, 0.53104258f, 0.64682180f,
        1.16943991f, 0.55310392f, 0.78815460f, 0.97256821f, 0.58293498f, 1.06067765f,
        0.83934963f, 0.62250412f, 1.72244716f, 0.74453628f, 0.67480832f, 5.10114861f
    };

    private static final float[] SYNTH_WINDOW = {
        -1f, 26f, -31f, 208f, 218f, 401f, -519f, 2063f, 2000f, 4788f, -5517f, 7134f, 5959f, 35640f, -39336f, 74992f,
        -1f, 24f, -35f, 202f, 222f, 347f, -581f, 2080f, 1952f, 4425f, -5879f, 7640f, 5288f, 33791f, -41176f, 74856f,
        -1f, 21f, -38f, 196f, 225f, 294f, -645f, 2087f, 1893f, 4063f, -6237f, 8092f, 4561f, 31947f, -43006f, 74630f,
        -1f, 19f, -41f, 190f, 227f, 244f, -711f, 2085f, 1822f, 3705f, -6589f, 8492f, 3776f, 30112f, -44821f, 74313f,
        -1f, 17f, -45f, 183f, 228f, 197f, -779f, 2075f, 1739f, 3351f, -6935f, 8840f, 2935f, 28289f, -46617f, 73908f,
        -1f, 16f, -49f, 176f, 228f, 153f, -848f, 2057f, 1644f, 3004f, -7271f, 9139f, 2037f, 26482f, -48390f, 73415f,
        -2f, 14f, -53f, 169f, 227f, 111f, -919f, 2032f, 1535f, 2663f, -7597f, 9389f, 1082f, 24694f, -50137f, 72835f,
        -2f, 13f, -58f, 161f, 224f, 72f, -991f, 2001f, 1414f, 2330f, -7910f, 9592f, 70f, 22929f, -51853f, 72169f,
        -2f, 11f, -63f, 154f, 221f, 36f, -1064f, 1962f, 1280f, 2006f, -8209f, 9750f, -998f, 21189f, -53534f, 71420f,
        -2f, 10f, -68f, 147f, 215f, 2f, -1137f, 1919f, 1131f, 1692f, -8491f, 9863f, -2122f, 19478f, -55178f, 70590f,
        -3f, 9f, -73f, 139f, 208f, -29f, -1210f, 1870f, 970f, 1388f, -8755f, 9935f, -3300f, 17799f, -56778f, 69679f,
        -3f, 8f, -79f, 132f, 200f, -57f, -1283f, 1817f, 794f, 1095f, -8998f, 9966f, -4533f, 16155f, -58333f, 68692f,
        -4f, 7f, -85f, 125f, 189f, -83f, -1356f, 1759f, 605f, 814f, -9219f, 9959f, -5818f, 14548f, -59838f, 67629f,
        -4f, 7f, -91f, 117f, 177f, -106f, -1428f, 1698f, 402f, 545f, -9416f, 9916f, -7154f, 12980f, -61289f, 66494f,
        -5f, 6f, -97f, 111f, 163f, -127f, -1498f, 1634f, 185f, 288f, -9585f, 9838f, -8540f, 11455f, -62684f, 65290f
    };

    // ---------------------------------------------------------------------------------
    // Estado
    // ---------------------------------------------------------------------------------

    private final float[] grbuf = new float[576 * 2];
    private final float[] syn = new float[(18 + 15) * 64];
    private final float[] qmfState = new float[15 * 64];
    private final float[] dctTemp = new float[32];
    private final float[] scf = new float[3 * 64];
    private final int[] bitalloc = new int[64];
    private final int[] scfcod = new int[64];
    private final float[] synthA = new float[4];
    private final float[] synthB = new float[4];
    private int totalBands;
    private int stereoBands;

    private byte[] bits;
    private int bitPos;
    private int bitLimit;

    /** Olvida el historial del banco de síntesis (búsqueda o discontinuidad). */
    void reset() {
        Arrays.fill(qmfState, 0f);
    }

    /**
     * Decodifica el cuadro de {@code header} que empieza en {@code offset} y escribe
     * {@link #SAMPLES_PER_FRAME} × canales muestras en {@code pcm} desde {@code pcmOffset}.
     * Devuelve false si el cuadro está truncado o dañado: entonces escribe silencio y reinicia
     * el estado, para que el audio siga en sincronía con el video.
     */
    boolean decodeFrame(byte[] data, int offset, Header header, short[] pcm, int pcmOffset) {
        int channels = header.channels;
        int end = Math.min(data.length, offset + header.frameBytes);
        bits = data;
        bitPos = (offset + HEADER_SIZE) * 8;
        bitLimit = end * 8;
        if (offset + header.frameBytes > data.length) return failFrame(pcm, pcmOffset, channels);
        if (header.crc) readBits(16);
        readScaleInfo(header);
        Arrays.fill(grbuf, 0f);
        for (int part = 0; part < 3; part++) {
            dequantizeGranule();
            applyScalefactors(part);
            synthGranule(channels, pcm, pcmOffset + part * 384 * channels);
            Arrays.fill(grbuf, 0f);
            if (bitPos > bitLimit) return failFrame(pcm, pcmOffset, channels);
        }
        return true;
    }

    private boolean failFrame(short[] pcm, int pcmOffset, int channels) {
        Arrays.fill(pcm, pcmOffset, pcmOffset + SAMPLES_PER_FRAME * channels, (short) 0);
        reset();
        return false;
    }

    private int readBits(int count) {
        int start = bitPos;
        bitPos += count;
        if (bitPos > bitLimit) return 0;
        int index = start >> 3;
        int window = (bits[index] & 0xFF) << 16;
        if (index + 1 < bits.length) window |= (bits[index + 1] & 0xFF) << 8;
        if (index + 2 < bits.length) window |= bits[index + 2] & 0xFF;
        return ((window << (start & 7)) & 0xFFFFFF) >>> (24 - count);
    }

    private void readScaleInfo(Header header) {
        int[][] alloc;
        int bands;
        int stereo = header.mode == MODE_MONO ? 0
                : header.mode == MODE_JOINT_STEREO ? (header.modeExtension << 2) + 4 : 32;
        if (!header.mpeg1) {
            alloc = ALLOC_MPEG2;
            bands = 30;
        } else {
            int kbps = header.bitrateKbps >> (header.mode != MODE_MONO ? 1 : 0);
            alloc = ALLOC_MPEG1;
            bands = 27;
            if (kbps < 56) {
                alloc = ALLOC_MPEG1_LOW_RATE;
                bands = header.sampleRateIndex == 2 ? 12 : 8;
            } else if (kbps >= 96 && header.sampleRateIndex != 1) {
                bands = 30;
            }
        }
        totalBands = bands;
        stereoBands = Math.min(stereo, bands);

        int nextGroup = 0;
        int group = 0;
        int codeBits = 0;
        int tabOffset = 0;
        for (int i = 0; i < totalBands; i++) {
            if (i == nextGroup) {
                tabOffset = alloc[group][0];
                codeBits = alloc[group][1];
                nextGroup += alloc[group][2];
                group++;
            }
            int ba = BITALLOC_CODE_TAB[tabOffset + readBits(codeBits)];
            bitalloc[2 * i] = ba;
            if (i < stereoBands) ba = BITALLOC_CODE_TAB[tabOffset + readBits(codeBits)];
            bitalloc[2 * i + 1] = stereoBands != 0 ? ba : 0;
        }
        for (int i = 0; i < 2 * totalBands; i++) {
            scfcod[i] = bitalloc[i] != 0 ? readBits(2) : 6;
        }
        readScalefactors(2 * totalBands);
        // Sobre el límite estéreo, el canal derecho copia las muestras del izquierdo.
        for (int i = stereoBands; i < totalBands; i++) bitalloc[2 * i + 1] = 0;
    }

    private void readScalefactors(int bands) {
        int out = 0;
        for (int i = 0; i < bands; i++) {
            float value = 0f;
            int ba = bitalloc[i];
            int mask = ba != 0 ? 4 + ((19 >> scfcod[i]) & 3) : 0;
            for (int m = 4; m != 0; m >>= 1) {
                if ((mask & m) != 0) {
                    int b = readBits(6);
                    value = DEQUANT[ba * 3 - 6 + b % 3] * (float) ((1 << 21) >> (b / 3));
                }
                scf[out++] = value;
            }
        }
    }

    /** Lee 4 gránulos de 3 muestras por subbanda y canal (12 muestras). */
    private void dequantizeGranule() {
        int channelOffset = 576;
        for (int j = 0; j < 4; j++) {
            int dst = 3 * j;
            for (int i = 0; i < 2 * totalBands; i++) {
                int ba = bitalloc[i];
                if (ba != 0) {
                    if (ba < 17) {
                        int half = (1 << (ba - 1)) - 1;
                        for (int k = 0; k < 3; k++) grbuf[dst + k] = readBits(ba) - half;
                    } else {
                        int levels = (2 << (ba - 17)) + 1;
                        int code = readBits(levels + 2 - (levels >> 3));
                        for (int k = 0; k < 3; k++, code /= levels) {
                            grbuf[dst + k] = code % levels - levels / 2;
                        }
                    }
                }
                dst += channelOffset;
                channelOffset = 18 - channelOffset;
            }
        }
    }

    private void applyScalefactors(int part) {
        int shared = stereoBands * 18;
        System.arraycopy(grbuf, shared, grbuf, 576 + shared, (totalBands - stereoBands) * 18);
        int s = part;
        for (int i = 0, dst = 0; i < totalBands; i++, dst += 18, s += 6) {
            float left = scf[s];
            float right = scf[s + 3];
            for (int k = 0; k < 12; k++) {
                grbuf[dst + k] *= left;
                grbuf[dst + k + 576] *= right;
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Banco de síntesis (minimp3: DCT-II de 32 puntos + ventana polifásica)
    // ---------------------------------------------------------------------------------

    private void synthGranule(int channels, short[] pcm, int pcmOffset) {
        for (int ch = 0; ch < channels; ch++) dctII(576 * ch, 12);
        System.arraycopy(qmfState, 0, syn, 0, 15 * 64);
        for (int i = 0; i < 12; i += 2) {
            synth(i, pcm, pcmOffset + 32 * channels * i, channels, i * 64);
        }
        System.arraycopy(syn, 12 * 64, qmfState, 0, 15 * 64);
    }

    private void dctII(int base, int count) {
        float[] g = grbuf;
        float[] t = dctTemp;
        for (int k = 0; k < count; k++) {
            int y = base + k;
            for (int i = 0; i < 8; i++) {
                float x0 = g[y + i * 18];
                float x1 = g[y + (15 - i) * 18];
                float x2 = g[y + (16 + i) * 18];
                float x3 = g[y + (31 - i) * 18];
                float t0 = x0 + x3;
                float t1 = x1 + x2;
                float t2 = (x1 - x2) * DCT_SECANTS[3 * i];
                float t3 = (x0 - x3) * DCT_SECANTS[3 * i + 1];
                t[i] = t0 + t1;
                t[8 + i] = (t0 - t1) * DCT_SECANTS[3 * i + 2];
                t[16 + i] = t3 + t2;
                t[24 + i] = (t3 - t2) * DCT_SECANTS[3 * i + 2];
            }
            for (int i = 0; i < 4; i++) {
                int x = 8 * i;
                float x0 = t[x], x1 = t[x + 1], x2 = t[x + 2], x3 = t[x + 3];
                float x4 = t[x + 4], x5 = t[x + 5], x6 = t[x + 6], x7 = t[x + 7], xt;
                xt = x0 - x7; x0 += x7;
                x7 = x1 - x6; x1 += x6;
                x6 = x2 - x5; x2 += x5;
                x5 = x3 - x4; x3 += x4;
                x4 = x0 - x3; x0 += x3;
                x3 = x1 - x2; x1 += x2;
                t[x] = x0 + x1;
                t[x + 4] = (x0 - x1) * 0.70710677f;
                x5 = x5 + x6;
                x6 = (x6 + x7) * 0.70710677f;
                x7 = x7 + xt;
                x3 = (x3 + x4) * 0.70710677f;
                x5 -= x7 * 0.198912367f;
                x7 += x5 * 0.382683432f;
                x5 -= x7 * 0.198912367f;
                x0 = xt - x6; xt += x6;
                t[x + 1] = (xt + x7) * 0.50979561f;
                t[x + 2] = (x4 + x3) * 0.54119611f;
                t[x + 3] = (x0 - x5) * 0.60134488f;
                t[x + 5] = (x0 + x5) * 0.89997619f;
                t[x + 6] = (x4 - x3) * 1.30656302f;
                t[x + 7] = (xt - x7) * 2.56291556f;
            }
            for (int i = 0; i < 7; i++, y += 4 * 18) {
                g[y] = t[i];
                g[y + 18] = t[16 + i] + t[24 + i] + t[24 + i + 1];
                g[y + 36] = t[8 + i] + t[8 + i + 1];
                g[y + 54] = t[16 + i + 1] + t[24 + i] + t[24 + i + 1];
            }
            g[y] = t[7];
            g[y + 18] = t[16 + 7] + t[24 + 7];
            g[y + 36] = t[8 + 7];
            g[y + 54] = t[24 + 7];
        }
    }

    private void synthPair(short[] pcm, int dst, int channels, int z) {
        float[] s = syn;
        float a;
        a = (s[z + 14 * 64] - s[z]) * 29;
        a += (s[z + 64] + s[z + 13 * 64]) * 213;
        a += (s[z + 12 * 64] - s[z + 2 * 64]) * 459;
        a += (s[z + 3 * 64] + s[z + 11 * 64]) * 2037;
        a += (s[z + 10 * 64] - s[z + 4 * 64]) * 5153;
        a += (s[z + 5 * 64] + s[z + 9 * 64]) * 6574;
        a += (s[z + 8 * 64] - s[z + 6 * 64]) * 37489;
        a += s[z + 7 * 64] * 75038;
        pcm[dst] = toPcm(a);

        z += 2;
        a = s[z + 14 * 64] * 104;
        a += s[z + 12 * 64] * 1567;
        a += s[z + 10 * 64] * 9727;
        a += s[z + 8 * 64] * 64019;
        a += s[z + 6 * 64] * -9975;
        a += s[z + 4 * 64] * -45;
        a += s[z + 2 * 64] * 146;
        a += s[z] * -5;
        pcm[dst + 16 * channels] = toPcm(a);
    }

    private void synth(int xl, short[] pcm, int dstl, int channels, int lins) {
        float[] g = grbuf;
        float[] s = syn;
        int xr = xl + 576 * (channels - 1);
        int dstr = dstl + (channels - 1);
        int zlin = lins + 15 * 64;

        s[zlin + 4 * 15] = g[xl + 18 * 16];
        s[zlin + 4 * 15 + 1] = g[xr + 18 * 16];
        s[zlin + 4 * 15 + 2] = g[xl];
        s[zlin + 4 * 15 + 3] = g[xr];

        s[zlin + 4 * 31] = g[xl + 1 + 18 * 16];
        s[zlin + 4 * 31 + 1] = g[xr + 1 + 18 * 16];
        s[zlin + 4 * 31 + 2] = g[xl + 1];
        s[zlin + 4 * 31 + 3] = g[xr + 1];

        synthPair(pcm, dstr, channels, lins + 4 * 15 + 1);
        synthPair(pcm, dstr + 32 * channels, channels, lins + 4 * 15 + 64 + 1);
        synthPair(pcm, dstl, channels, lins + 4 * 15);
        synthPair(pcm, dstl + 32 * channels, channels, lins + 4 * 15 + 64);

        float[] a = synthA;
        float[] b = synthB;
        int w = 0;
        for (int i = 14; i >= 0; i--) {
            s[zlin + 4 * i] = g[xl + 18 * (31 - i)];
            s[zlin + 4 * i + 1] = g[xr + 18 * (31 - i)];
            s[zlin + 4 * i + 2] = g[xl + 1 + 18 * (31 - i)];
            s[zlin + 4 * i + 3] = g[xr + 1 + 18 * (31 - i)];
            s[zlin + 4 * (i + 16)] = g[xl + 1 + 18 * (1 + i)];
            s[zlin + 4 * (i + 16) + 1] = g[xr + 1 + 18 * (1 + i)];
            s[zlin + 4 * (i - 16) + 2] = g[xl + 18 * (1 + i)];
            s[zlin + 4 * (i - 16) + 3] = g[xr + 18 * (1 + i)];

            for (int k = 0; k < 8; k++) {
                float w0 = SYNTH_WINDOW[w++];
                float w1 = SYNTH_WINDOW[w++];
                int vz = zlin + 4 * i - k * 64;
                int vy = zlin + 4 * i - (15 - k) * 64;
                for (int j = 0; j < 4; j++) {
                    float z = s[vz + j];
                    float y = s[vy + j];
                    if (k == 0) {
                        b[j] = z * w1 + y * w0;
                        a[j] = z * w0 - y * w1;
                    } else if ((k & 1) != 0) {
                        b[j] += z * w1 + y * w0;
                        a[j] += y * w1 - z * w0;
                    } else {
                        b[j] += z * w1 + y * w0;
                        a[j] += z * w0 - y * w1;
                    }
                }
            }

            pcm[dstr + (15 - i) * channels] = toPcm(a[1]);
            pcm[dstr + (17 + i) * channels] = toPcm(b[1]);
            pcm[dstl + (15 - i) * channels] = toPcm(a[0]);
            pcm[dstl + (17 + i) * channels] = toPcm(b[0]);
            pcm[dstr + (47 - i) * channels] = toPcm(a[3]);
            pcm[dstr + (49 + i) * channels] = toPcm(b[3]);
            pcm[dstl + (47 - i) * channels] = toPcm(a[2]);
            pcm[dstl + (49 + i) * channels] = toPcm(b[2]);
        }
    }

    private static short toPcm(float sample) {
        if (sample >= 32766.5f) return 32767;
        if (sample <= -32767.5f) return -32768;
        int value = (int) (sample + 0.5f);
        if (value < 0) value--;
        return (short) value;
    }
}
