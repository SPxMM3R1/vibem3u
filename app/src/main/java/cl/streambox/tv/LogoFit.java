package cl.streambox.tv;

import android.graphics.Bitmap;

import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tamaño óptico de logos: todos ocupan la misma superficie visual según su
 * proporción (un logo ancho queda más bajo, uno cuadrado más alto), con topes de
 * alto y ancho. Antes se recorta el borde transparente que traen algunos archivos,
 * para que ese margen no achique el logo.
 */
final class LogoFit {
    /**
     * Logo del OSD y del bloque de arriba de la Guía (dp). Desde la 0.5.59, 30 % más grande
     * que antes (75×23, tope 105×32): superficie 97,5×29,9 y tope 136,5×41,6.
     */
    static final float LOGO_SCALE = 1.3f;
    static final float OSD_LOGO_AREA_WIDTH_DP = 75f * LOGO_SCALE;
    static final float OSD_LOGO_AREA_HEIGHT_DP = 23f * LOGO_SCALE;
    static final float OSD_LOGO_MAX_WIDTH_DP = 105f * LOGO_SCALE;
    static final float OSD_LOGO_MAX_HEIGHT_DP = 32f * LOGO_SCALE;
    /** Alfa mínimo para considerar un píxel parte del logo. */
    private static final int ALPHA_THRESHOLD = 16;
    /**
     * Logos «…-uhd» (2026-10-03): son su logo normal más la caja «UHD», ~30 % más anchos.
     * Deben verse igual de altos que su versión normal y solo más largos, así que su alto se
     * calcula como si no tuvieran esa caja.
     */
    static final float UHD_WIDTH_FACTOR = 1.30f;
    private static final Map<Bitmap, Float> WIDTH_FACTORS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Corrección por tinta (0.5.60): dos logos con el mismo rectángulo no pesan lo mismo si
     * uno es sólido y otro de trazos finos. Se mide qué fracción del área queda pintada y se
     * acerca a medias a la mediana del catálogo (0,44, medida en 108 logos el 2026-10-04),
     * entre {@link #AUTO_SCALE_MIN} y {@link #AUTO_SCALE_MAX}.
     */
    static final float INK_MEDIAN_FRACTION = 0.44f;
    static final float AUTO_SCALE_MIN = 0.8f;
    static final float AUTO_SCALE_MAX = 1.25f;
    /** Ajuste a mano por logo (Opciones › En reproducción › Tamaño del logo). */
    static final float USER_SCALE_MIN = 0.5f;
    static final float USER_SCALE_MAX = 1.6f;
    private static final Map<Bitmap, Float> COVERAGE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Bitmap, String> LOGO_KEYS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Float> USER_SCALES = new ConcurrentHashMap<>();

    private LogoFit() {}

    /**
     * Tamaño {ancho, alto} con superficie {@code area} para un logo de
     * {@code width}×{@code height}, limitado a {@code maxWidth}×{@code maxHeight}.
     */
    static float[] opticalSize(int width, int height, float area, float maxWidth, float maxHeight) {
        return opticalSize(width, height, area, maxWidth, maxHeight, 1f);
    }

    /**
     * Como {@link #opticalSize(int, int, float, float, float)}, pero el alto se calcula con el
     * ancho dividido por {@code familyWidthFactor}: un logo que es «su versión normal más algo
     * a la derecha» queda igual de alto que esa versión y solo más largo.
     */
    static float[] opticalSize(int width, int height, float area, float maxWidth, float maxHeight,
                               float familyWidthFactor) {
        if (width <= 0 || height <= 0 || area <= 0f) return new float[] {0f, 0f};
        float aspect = width / (float) height;
        float factor = familyWidthFactor >= 1f ? familyWidthFactor : 1f;
        float h = (float) Math.sqrt(area / (aspect / factor));
        float w = aspect * h;
        if (h > maxHeight) {
            h = maxHeight;
            w = aspect * h;
        }
        if (w > maxWidth) {
            w = maxWidth;
            h = w / aspect;
        }
        return new float[] {w, h};
    }

    /** Recorte {izquierda, arriba, derecha, abajo} (exclusivo) sin bordes transparentes, o null. */
    static int[] opaqueBounds(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) return null;
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if ((argb[row + x] >>> 24) > ALPHA_THRESHOLD) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    bottom = y;
                }
            }
        }
        if (right < 0) return null;
        return new int[] {left, top, right + 1, bottom + 1};
    }

    /**
     * Tamaño óptico de un logo ya recortado: respeta si es la versión UHD de otro, la
     * corrección por tinta y el ajuste a mano guardado para ese logo.
     */
    static float[] opticalSize(Bitmap bitmap, float area, float maxWidth, float maxHeight) {
        if (bitmap == null) return new float[] {0f, 0f};
        Float factor = WIDTH_FACTORS.get(bitmap);
        Float coverage = COVERAGE.get(bitmap);
        String key = LOGO_KEYS.get(bitmap);
        return scaledSize(bitmap.getWidth(), bitmap.getHeight(), area, maxWidth, maxHeight,
                factor == null ? 1f : factor, coverage == null ? -1f : coverage, userScale(key));
    }

    /**
     * Tamaño base, luego corregido por tinta ({@code coverage} menor que 0: sin medir) y por
     * el ajuste a mano. Superficie y topes crecen o se achican juntos, sin deformar.
     */
    static float[] scaledSize(int width, int height, float area, float maxWidth, float maxHeight,
                              float familyWidthFactor, float coverage, float userScale) {
        float[] base = opticalSize(width, height, area, maxWidth, maxHeight, familyWidthFactor);
        float scale = userScale;
        if (coverage >= 0f && area > 0f) {
            scale *= autoScale(coverage * base[0] * base[1] / area);
        }
        if (Math.abs(scale - 1f) < 0.001f) return base;
        return opticalSize(width, height, area * scale * scale, maxWidth * scale,
                maxHeight * scale, familyWidthFactor);
    }

    /** Escala automática para un logo que pinta {@code inkFraction} de su superficie. */
    static float autoScale(float inkFraction) {
        if (!(inkFraction > 0f)) return AUTO_SCALE_MAX;
        float value = (float) Math.pow(INK_MEDIAN_FRACTION / inkFraction, 0.25);
        return Math.max(AUTO_SCALE_MIN, Math.min(AUTO_SCALE_MAX, value));
    }

    /** Fracción pintada (alfa promedio) de un logo ya recortado; muestrea a lo más ~40 000 px. */
    static float inkCoverage(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) return -1f;
        int step = Math.max(1, (int) Math.sqrt((width * (long) height) / 40_000.0));
        double ink = 0;
        long samples = 0;
        for (int y = 0; y < height; y += step) {
            int row = y * width;
            for (int x = 0; x < width; x += step) {
                ink += (argb[row + x] >>> 24) / 255.0;
                samples++;
            }
        }
        return samples == 0 ? -1f : (float) (ink / samples);
    }

    /** Ajuste a mano de un logo (1 = sin ajuste). */
    static float userScale(String logoKey) {
        if (logoKey == null) return 1f;
        Float value = USER_SCALES.get(logoKey);
        return value == null ? 1f : value;
    }

    /** Cambia el ajuste en memoria (en vivo); 1 lo quita. Lo guarda LogoScales. */
    static void setUserScale(String logoKey, float scale) {
        if (logoKey == null) return;
        float clamped = Math.max(USER_SCALE_MIN, Math.min(USER_SCALE_MAX, scale));
        if (Math.abs(clamped - 1f) < 0.001f) USER_SCALES.remove(logoKey);
        else USER_SCALES.put(logoKey, clamped);
    }

    static void replaceUserScales(Map<String, Float> scales) {
        USER_SCALES.clear();
        if (scales == null) return;
        for (Map.Entry<String, Float> entry : scales.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                setUserScale(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Clave con la que se guarda el ajuste de un logo: su dirección. */
    static String logoKey(URI logoUri) {
        return logoUri == null ? null : logoUri.toString();
    }

    /** Factor de ancho de familia según el archivo: «…-uhd.png» → {@link #UHD_WIDTH_FACTOR}. */
    static float familyWidthFactor(URI logoUri) {
        String path = logoUri == null || logoUri.getPath() == null
                ? "" : logoUri.getPath().toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.endsWith("-uhd") ? UHD_WIDTH_FACTOR : 1f;
    }

    /** Recorta y recuerda el factor de familia del logo (por su nombre de archivo). */
    static Bitmap trim(Bitmap bitmap, URI logoUri) {
        Bitmap trimmed = trim(bitmap);
        // El logo se muestra 2–3 veces más chico que el bitmap: con mipmaps la TV lo achica
        // con buen filtro y no salta píxeles (líneas finas dentadas).
        if (trimmed != null) trimmed.setHasMipMap(true);
        float factor = familyWidthFactor(logoUri);
        if (trimmed != null && factor > 1f) WIDTH_FACTORS.put(trimmed, factor);
        if (trimmed != null && !trimmed.isRecycled()) {
            String key = logoKey(logoUri);
            if (key != null) LOGO_KEYS.put(trimmed, key);
            float coverage = 1f;
            if (trimmed.hasAlpha()) {
                int width = trimmed.getWidth();
                int height = trimmed.getHeight();
                int[] pixels = new int[width * height];
                trimmed.getPixels(pixels, 0, width, 0, 0, width, height);
                coverage = inkCoverage(pixels, width, height);
            }
            if (coverage >= 0f) COVERAGE.put(trimmed, coverage);
        }
        return trimmed;
    }

    /** Devuelve el logo sin borde transparente (el mismo bitmap si no hay nada que recortar). */
    static Bitmap trim(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || !bitmap.hasAlpha()) return bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] bounds = opaqueBounds(pixels, width, height);
        if (bounds == null
                || (bounds[0] == 0 && bounds[1] == 0 && bounds[2] == width && bounds[3] == height)) {
            return bitmap;
        }
        return Bitmap.createBitmap(bitmap, bounds[0], bounds[1],
                bounds[2] - bounds[0], bounds[3] - bounds[1]);
    }
}
