package cl.streambox.tv;

import android.graphics.Bitmap;

import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

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

    /** Tamaño óptico de un logo ya recortado, respetando si es la versión UHD de otro. */
    static float[] opticalSize(Bitmap bitmap, float area, float maxWidth, float maxHeight) {
        if (bitmap == null) return new float[] {0f, 0f};
        Float factor = WIDTH_FACTORS.get(bitmap);
        return opticalSize(bitmap.getWidth(), bitmap.getHeight(), area, maxWidth, maxHeight,
                factor == null ? 1f : factor);
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
