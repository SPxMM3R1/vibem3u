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
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SharedPreferences preferences;

    GitHubCredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean linked() {
        return preferences.contains(KEY_CIPHERTEXT) && preferences.contains(KEY_IV);
    }

    /** Token descifrado o null si no hay o el almacén ya no puede abrirlo. */
    String token() {
        String ciphertext = preferences.getString(KEY_CIPHERTEXT, null);
        String iv = preferences.getString(KEY_IV, null);
        if (ciphertext == null || iv == null) return null;
        try {
            return decrypt(ciphertext, iv);
        } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
            clear();
            return null;
        }
    }

    void save(String token) throws GeneralSecurityException {
        if (!isPlausibleToken(token)) throw new GeneralSecurityException("Token de GitHub inválido.");
        byte[][] sealed = encrypt(token);
        preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(sealed[0], Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(sealed[1], Base64.NO_WRAP))
                .apply();
    }

    void clear() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply();
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
