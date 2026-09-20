package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the optional GitHub credential encrypted with Android Keystore. */
public final class GitHubPublicationPreferences {
    public static final String PREFS = "github_publication";
    private static final String KEY_AUTO_PUBLISH = "auto_publish";
    private static final String KEY_TOKEN = "token_cipher";
    private static final String KEY_LAST_ATTEMPT_SIGNATURE = "last_attempt_signature";
    private static final String KEY_LAST_ATTEMPT_AT = "last_attempt_at";
    private static final String KEY_LAST_PUBLISHED_SIGNATURE = "last_published_signature";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_ALIAS = "cl.streambox.tv.github.contents";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int IV_BYTES = 12;

    private final SharedPreferences preferences;

    public GitHubPublicationPreferences(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isAutoPublishEnabled() {
        return preferences.getBoolean(KEY_AUTO_PUBLISH, false);
    }

    public void setAutoPublishEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_PUBLISH, enabled).apply();
    }

    public boolean hasToken() {
        return !AppStrings.isBlank(preferences.getString(KEY_TOKEN, ""))
                && !AppStrings.isBlank(getToken());
    }

    /** Decrypts only for the immediate GitHub request. */
    public String getToken() {
        String encoded = preferences.getString(KEY_TOKEN, "");
        if (AppStrings.isBlank(encoded)) return "";
        byte[] packed = null;
        byte[] plaintext = null;
        try {
            packed = Base64.getDecoder().decode(encoded);
            if (packed.length <= IV_BYTES) return "";
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        } finally {
            if (packed != null) java.util.Arrays.fill(packed, (byte) 0);
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Never writes the token in plaintext or in a normal preference value. */
    public void setToken(String token) throws GeneralSecurityException {
        if (token == null || token.trim().isEmpty()) {
            preferences.edit().remove(KEY_TOKEN).apply();
            clearAttemptState();
            return;
        }
        String normalized = token.trim();
        if (normalized.length() < 20 || normalized.length() > 512
                || containsControlCharacter(normalized)) {
            throw new GeneralSecurityException("Token de GitHub inválido.");
        }

        byte[] plaintext = normalized.getBytes(StandardCharsets.UTF_8);
        byte[] iv = null;
        byte[] ciphertext = null;
        byte[] packed = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            iv = cipher.getIV();
            if (iv == null || iv.length == 0) {
                throw new GeneralSecurityException("Keystore no generó IV.");
            }
            ciphertext = cipher.doFinal(plaintext);
            packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            preferences.edit()
                    .putString(KEY_TOKEN, Base64.getEncoder().encodeToString(packed))
                    .apply();
            clearAttemptState();
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
            if (iv != null) java.util.Arrays.fill(iv, (byte) 0);
            if (ciphertext != null) java.util.Arrays.fill(ciphertext, (byte) 0);
            if (packed != null) java.util.Arrays.fill(packed, (byte) 0);
        }
    }

    public String getLastPublishedSignature() {
        return preferences.getString(KEY_LAST_PUBLISHED_SIGNATURE, "");
    }

    public String getLastStatus() {
        return preferences.getString(KEY_LAST_STATUS, "");
    }

    public boolean shouldAttempt(String signature, long now, boolean force) {
        if (force) return true;
        if (signature == null || signature.isEmpty()) return true;
        if (signature.equals(getLastPublishedSignature())) return false;
        String attempted = preferences.getString(KEY_LAST_ATTEMPT_SIGNATURE, "");
        long attemptedAt = preferences.getLong(KEY_LAST_ATTEMPT_AT, 0L);
        return !signature.equals(attempted)
                || now - attemptedAt >= GitHubSelectionPublisher.RETRY_COOLDOWN_MILLIS;
    }

    public void markAttempt(String signature, long now) {
        preferences.edit()
                .putString(KEY_LAST_ATTEMPT_SIGNATURE, signature == null ? "" : signature)
                .putLong(KEY_LAST_ATTEMPT_AT, now)
                .apply();
    }

    public void markPublished(String signature) {
        preferences.edit()
                .putString(KEY_LAST_PUBLISHED_SIGNATURE, signature == null ? "" : signature)
                .putString(KEY_LAST_STATUS, "Publicado en GitHub")
                .apply();
    }

    public void markFailure(String status) {
        preferences.edit()
                .putString(KEY_LAST_STATUS, status == null ? "Error de publicación" : status)
                .apply();
    }

    private void clearAttemptState() {
        preferences.edit()
                .remove(KEY_LAST_ATTEMPT_SIGNATURE)
                .remove(KEY_LAST_ATTEMPT_AT)
                .remove(KEY_LAST_PUBLISHED_SIGNATURE)
                .remove(KEY_LAST_STATUS)
                .apply();
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        try {
            store.load(null);
        } catch (java.io.IOException error) {
            KeyStoreException wrapped = new KeyStoreException(
                    "No se pudo abrir Android Keystore."
            );
            wrapped.initCause(error);
            throw wrapped;
        }
        if (store.containsAlias(KEY_ALIAS)) {
            java.security.Key existing = store.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
