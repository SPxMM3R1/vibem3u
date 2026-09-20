package cl.streambox.tv;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

/** Small QR renderer for the GitHub device authorization dialog. */
public final class QrCodeBitmap {
    private QrCodeBitmap() {}

    public static Bitmap encode(String value, int size) throws Exception {
        if (AppStrings.isBlank(value)) throw new IllegalArgumentException("value");
        int dimension = Math.max(128, Math.min(1024, size));
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new MultiFormatWriter().encode(
                value,
                BarcodeFormat.QR_CODE,
                dimension,
                dimension,
                hints
        );
        Bitmap bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }
}
