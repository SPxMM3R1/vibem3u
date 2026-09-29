package cl.streambox.tv;

import android.graphics.Bitmap;

/**
 * Tamaño óptico de logos: todos ocupan la misma superficie visual según su
 * proporción (un logo ancho queda más bajo, uno cuadrado más alto), con topes de
 * alto y ancho. Antes se recorta el borde transparente que traen algunos archivos,
 * para que ese margen no achique el logo.
 */
final class LogoFit {
    /** Alfa mínimo para considerar un píxel parte del logo. */
    private static final int ALPHA_THRESHOLD = 16;

    private LogoFit() {}

    /**
     * Tamaño {ancho, alto} con superficie {@code area} para un logo de
     * {@code width}×{@code height}, limitado a {@code maxWidth}×{@code maxHeight}.
     */
    static float[] opticalSize(int width, int height, float area, float maxWidth, float maxHeight) {
        if (width <= 0 || height <= 0 || area <= 0f) return new float[] {0f, 0f};
        float aspect = width / (float) height;
        float h = (float) Math.sqrt(area / aspect);
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
