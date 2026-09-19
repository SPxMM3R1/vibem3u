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

/** User-owned MediaFlow configuration with the API password in Android Keystore. */
public final class MediaFlowPreferences {
    public static final String PREFS = "streambox_settings";
    public static final String KEY_ENABLED = "mediaflow_enabled";
    public static final String KEY_ORIGIN = "mediaflow_origin";
    public static final String KEY_REVISION = "mediaflow_config_revision";
    private static final String KEY_PASSWORD = "mediaflow_api_password_cipher";
    private static final String KEY_ALIAS = "cl.streambox.tv.mediaflow.api";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int IV_BYTES = 12;

    private final SharedPreferences preferences;
    public MediaFlowPreferences(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    /** Disabling leaves the encrypted origin/password intact for later re-enable. */
    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
        bumpRevision();
    }

    /** Non-secret monotonic marker for rebuilding the resolver after menu changes. */
    public long getRevision() {
        return preferences.getLong(KEY_REVISION, 0L);
    }

    public String getOrigin() {
        String value = preferences.getString(KEY_ORIGIN, "");
        if (AppStrings.isBlank(value)) return "";
        try {
            return MediaFlowOriginPolicy.parseOrigin(value).toString();
        } catch (java.io.IOException ignored) {
            return "";
        }
    }

    public java.net.URI getOriginUri() {
        String origin = getOrigin();
        if (AppStrings.isBlank(origin)) return null;
        try {
            return MediaFlowOriginPolicy.parseOrigin(origin);
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    public void setOrigin(String origin) throws java.io.IOException {
        java.net.URI normalized = MediaFlowOriginPolicy.parseOrigin(origin);
        preferences.edit().putString(KEY_ORIGIN, normalized.toString()).apply();
        bumpRevision();
    }

    public boolean hasApiPassword() {
        return !AppStrings.isBlank(preferences.getString(KEY_PASSWORD, ""));
    }

    /** Non-secret configuration revision used to invalidate playback wiring. */
    public String signature() {
        return (isEnabled() ? "1" : "0")
                + "|" + getOrigin()
                + "|password=" + (hasApiPassword() ? "1" : "0");
    }

    /**
     * Decrypts the password only for the immediate request that needs it.
     * Invalid/corrupt ciphertext fails closed and returns an empty value.
     */
    public String getApiPassword() {
        String encoded = preferences.getString(KEY_PASSWORD, "");
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

    /** Stores no plaintext fallback if the Android Keystore is unavailable. */
    public void setApiPassword(String password) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            preferences.edit().remove(KEY_PASSWORD).apply();
            bumpRevision();
            return;
        }
        byte[] plaintext = password.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = null;
        byte[] packed = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Android Keystore requires a fresh provider-generated IV when
            // randomized encryption is enabled. Never supply a caller IV.
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
                    .putString(KEY_PASSWORD, Base64.getEncoder().encodeToString(packed))
                    .apply();
            bumpRevision();
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
            java.util.Arrays.fill(iv, (byte) 0);
            if (ciphertext != null) java.util.Arrays.fill(ciphertext, (byte) 0);
            if (packed != null) java.util.Arrays.fill(packed, (byte) 0);
        }
    }

    private void bumpRevision() {
        long previous = preferences.getLong(KEY_REVISION, 0L);
        long now = System.currentTimeMillis();
        preferences.edit().putLong(KEY_REVISION, Math.max(now, previous + 1L)).apply();
    }

    private SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        try {
            store.load(null);
        } catch (java.io.IOException error) {
            KeyStoreException wrapped = new KeyStoreException("No se pudo abrir Android Keystore.");
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
