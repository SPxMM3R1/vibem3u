package cl.streambox.tv;

import java.io.IOException;

/** Validation rules for the token path used by the Highfly Premium service. */
final class HighflyPremiumTokenRules {
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 256;

    private HighflyPremiumTokenRules() {}

    static String normalize(String value) throws IOException {
        String token = value == null ? "" : value.trim();
        if (!isValid(token)) {
            throw new IOException("La credencial Premium no tiene un formato válido.");
        }
        return token;
    }

    static boolean isValid(String value) {
        if (value == null || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return false;
        }
        // The provider uses the token as one URL path segment. Rejecting path
        // delimiters, percent escapes, quotes and control characters prevents
        // a pasted value from changing the endpoint that receives it.
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean safe = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '_'
                    || character == '-'
                    || character == '~'
                    || character == '+'
                    || character == '=';
            if (!safe) return false;
        }
        return true;
    }
}
