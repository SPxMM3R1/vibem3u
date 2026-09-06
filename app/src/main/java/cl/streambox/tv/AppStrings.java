package cl.streambox.tv;

/** Small string helpers kept independent of newer Android framework APIs. */
final class AppStrings {
    private AppStrings() {}

    /** Equivalent to {@code AppStrings.isBlank(String)} without requiring API 33. */
    static boolean isBlank(String value) {
        if (value == null || value.length() == 0) return true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
