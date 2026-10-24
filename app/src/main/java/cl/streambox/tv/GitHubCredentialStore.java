package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Acceso a GitHub de esta TV (0.5.90), para enviar canales a la papelera de Lista M3U. El token
 * de la vinculación por código se guarda cifrado con AES-GCM y clave del Android Keystore, igual
 * que el token Premium; nunca se muestra ni se registra.
 */
final class GitHubCredentialStore {
    private static final String PREFS = "github_link";
    private static final String KEY_ALIAS = "vibem3u_github_link";
    private static final String KEY_CIPHERTEXT = "github_token_ciphertext";
    private static final String KEY_IV = "github_token_iv";
    private static final String KEY_REFRESH_CIPHERTEXT = "github_refresh_ciphertext";
    private static final String KEY_REFRESH_IV = "github_refresh_iv";
    private static final String KEY_ACCESS_EXPIRES_AT = "github_access_expires_at";
    static final Object AUTH_LOCK = new Object();
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SharedPreferences preferences;

    /** Credenciales de sesión OAuth. Los dos tokens sensibles permanecen cifrados. */
    static final class Credentials {
        final String accessToken;
        final String refreshToken;
        final long accessExpiresAtMillis;

        Credentials(String accessToken, String refreshToken, long accessExpiresAtMillis) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.accessExpiresAtMillis = Math.max(0L, accessExpiresAtMillis);
        }
    }

    GitHubCredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean linked() {
        synchronized (AUTH_LOCK) {
            return preferences.contains(KEY_CIPHERTEXT) && preferences.contains(KEY_IV);
        }
    }

    /** Token descifrado o null si no hay o el almacén ya no puede abrirlo. */
    String token() {
        Credentials credentials = credentials();
        return credentials == null ? null : credentials.accessToken;
    }

    /** Credenciales descifradas o null si el acceso ya no se puede recuperar. */
    Credentials credentials() {
        synchronized (AUTH_LOCK) {
            return readCredentialsLocked();
        }
    }

    private Credentials readCredentialsLocked() {
        String ciphertext = preferences.getString(KEY_CIPHERTEXT, null);
        String iv = preferences.getString(KEY_IV, null);
        if (ciphertext == null || iv == null) return null;
        try {
            String accessToken = decrypt(ciphertext, iv);
            if (!isPlausibleToken(accessToken)) throw new GeneralSecurityException("Credencial inválida.");
            String refreshToken = readRefreshToken();
            return new Credentials(accessToken, refreshToken,
                    preferences.getLong(KEY_ACCESS_EXPIRES_AT, 0L));
        } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
            clear();
            return null;
        }
    }

    void save(String token) throws GeneralSecurityException {
        save(new Credentials(token, null, 0L));
    }

    /** Persiste también la rotación del refresh token antes de usar el nuevo access token. */
    void save(Credentials credentials) throws GeneralSecurityException {
        synchronized (AUTH_LOCK) {
            saveLocked(credentials);
        }
    }

    private void saveLocked(Credentials credentials) throws GeneralSecurityException {
        if (credentials == null || !isPlausibleToken(credentials.accessToken)) {
            throw new GeneralSecurityException("Token de GitHub inválido.");
        }
        byte[][] access = encrypt(credentials.accessToken);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(access[0], Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(access[1], Base64.NO_WRAP))
                .putLong(KEY_ACCESS_EXPIRES_AT, credentials.accessExpiresAtMillis);
        if (!AppStrings.isBlank(credentials.refreshToken)) {
            if (!isPlausibleToken(credentials.refreshToken)) {
                throw new GeneralSecurityException("Refresh token de GitHub inválido.");
            }
            byte[][] refresh = encrypt(credentials.refreshToken);
            editor.putString(KEY_REFRESH_CIPHERTEXT, Base64.encodeToString(refresh[0], Base64.NO_WRAP))
                    .putString(KEY_REFRESH_IV, Base64.encodeToString(refresh[1], Base64.NO_WRAP));
        } else {
            editor.remove(KEY_REFRESH_CIPHERTEXT).remove(KEY_REFRESH_IV);
        }
        if (!editor.commit()) throw new GeneralSecurityException("No se pudo guardar el acceso de GitHub.");
    }

    void clear() {
        synchronized (AUTH_LOCK) {
            preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV)
                    .remove(KEY_REFRESH_CIPHERTEXT).remove(KEY_REFRESH_IV)
                    .remove(KEY_ACCESS_EXPIRES_AT).apply();
        }
    }

    private String readRefreshToken() {
        String ciphertext = preferences.getString(KEY_REFRESH_CIPHERTEXT, null);
        String iv = preferences.getString(KEY_REFRESH_IV, null);
        if (ciphertext == null && iv == null) return null;
        if (ciphertext == null || iv == null) {
            clearRefreshToken();
            return null;
        }
        try {
            String token = decrypt(ciphertext, iv);
            if (!isPlausibleToken(token)) throw new GeneralSecurityException("Refresh token inválido.");
            return token;
        } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
            // Un refresh token dañado no debe borrar un access token que aún sea válido.
            clearRefreshToken();
            return null;
        }
    }

    private void clearRefreshToken() {
        preferences.edit().remove(KEY_REFRESH_CIPHERTEXT).remove(KEY_REFRESH_IV)
                .remove(KEY_ACCESS_EXPIRES_AT).apply();
    }

    static boolean isPlausibleToken(String token) {
        return token != null && token.length() >= 20 && token.length() <= 255
                && token.matches("[A-Za-z0-9_\\-]+");
    }

    private byte[][] encrypt(String token) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // El Keystore exige que el IV lo genere el propio cifrador (API 29+).
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new GeneralSecurityException("El almacén seguro devolvió un IV inválido.");
        }
        byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
        try {
            return new byte[][]{cipher.doFinal(plaintext), iv};
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private String decrypt(String ciphertext, String iv) throws GeneralSecurityException {
        byte[] encrypted = Base64.decode(ciphertext, Base64.DEFAULT);
        byte[] ivBytes = Base64.decode(iv, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, ivBytes));
        byte[] plaintext = cipher.doFinal(encrypted);
        try {
            String token = new String(plaintext, StandardCharsets.UTF_8);
            if (!isPlausibleToken(token)) throw new GeneralSecurityException("Credencial inválida.");
            return token;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (IOException error) {
            throw new GeneralSecurityException("No se pudo abrir el almacén seguro.", error);
        }
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
