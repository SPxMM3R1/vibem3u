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
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the user supplied Highfly Premium credential without exposing it to
 * the settings UI or to the rest of the application configuration.
 *
 * <p>The credential is always stored as an AES-GCM ciphertext and IV in the
 * app's private preferences; the key remains in Android Keystore. A decrypted
 * copy may exist briefly in RAM while the app is running and is cleared when
 * the app releases its session. Backups are disabled in the manifest because
 * even an encrypted credential should not be copied to another device.</p>
 */
public final class HighflyPremiumCredentialStore {
    private static final String KEY_ALIAS = "vibem3u_highfly_premium";
    // Kept only to remove the obsolete setting left by versions that exposed
    // a RAM-only mode. It is never read as a source of truth anymore.
    private static final String LEGACY_KEY_STORAGE_MODE = "highfly_premium_storage_mode";
    private static final String KEY_CIPHERTEXT = "highfly_premium_token_ciphertext";
    private static final String KEY_IV = "highfly_premium_token_iv";
    private static final String KEY_STATUS = "highfly_premium_status";
    private static final String KEY_PLAN = "highfly_premium_plan";
    private static final String KEY_EXPIRES_AT = "highfly_premium_expires_at";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile HighflyPremiumCredentialStore instance;

    private final SharedPreferences preferences;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object lock = new Object();
    private char[] memoryToken;
    private long generation;
    private TokenStatus status;

    private HighflyPremiumCredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        status = readStoredStatus();
    }

    public static HighflyPremiumCredentialStore getInstance(Context context) {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new HighflyPremiumCredentialStore(context);
                }
            }
        }
        return instance;
    }

    public enum Status {
        NOT_CONFIGURED,
        UNKNOWN,
        VALID,
        INVALID,
        EXPIRED
    }

    public static final class TokenStatus {
        private final Status status;
        private final String planName;
        private final long expiresAtMillis;

        TokenStatus(Status status, String planName, long expiresAtMillis) {
            this.status = status == null ? Status.UNKNOWN : status;
            this.planName = planName == null ? "" : planName;
            this.expiresAtMillis = Math.max(0L, expiresAtMillis);
        }

        public Status getStatus() {
            return status;
        }

        public String getPlanName() {
            return planName;
        }

        public long getExpiresAtMillis() {
            return expiresAtMillis;
        }

        public boolean isConfigured() {
            return status != Status.NOT_CONFIGURED;
        }
    }

    public boolean hasCredential() {
        synchronized (lock) {
            if (memoryToken != null && memoryToken.length > 0) return true;
            return !preferences.getString(KEY_CIPHERTEXT, "").isBlank()
                    && !preferences.getString(KEY_IV, "").isBlank();
        }
    }

    /** Returns a short-lived request copy for the resolver, never for the UI. */
    String readTokenForRequest() {
        synchronized (lock) {
            if (memoryToken != null && memoryToken.length > 0) {
                return new String(memoryToken);
            }
            String ciphertext = preferences.getString(KEY_CIPHERTEXT, "");
            String iv = preferences.getString(KEY_IV, "");
            if (ciphertext.isBlank() || iv.isBlank()) return null;
            try {
                char[] decrypted = decrypt(ciphertext, iv);
                memoryToken = decrypted;
                return new String(decrypted);
            } catch (GeneralSecurityException | IllegalArgumentException error) {
                clearEncryptedDataLocked();
                status = new TokenStatus(Status.NOT_CONFIGURED, "", 0L);
                return null;
            }
        }
    }

    /** Saves the credential encrypted; there is intentionally no RAM-only mode. */
    public boolean saveToken(String value) throws IOException {
        String token = HighflyPremiumTokenRules.normalize(value);
        synchronized (lock) {
            try {
                byte[][] encrypted = encrypt(token);
                String ciphertext = Base64.encodeToString(encrypted[0], Base64.NO_WRAP);
                String iv = Base64.encodeToString(encrypted[1], Base64.NO_WRAP);
                boolean committed = preferences.edit()
                        .remove(LEGACY_KEY_STORAGE_MODE)
                        .putString(KEY_CIPHERTEXT, ciphertext)
                        .putString(KEY_IV, iv)
                        .remove(KEY_STATUS)
                        .remove(KEY_PLAN)
                        .remove(KEY_EXPIRES_AT)
                        .commit();
                if (!committed) throw new IOException("No se pudo guardar la credencial.");
            } catch (GeneralSecurityException error) {
                throw new IOException("No se pudo proteger la credencial.");
            }
            replaceMemoryTokenLocked(token);
            status = new TokenStatus(Status.UNKNOWN, "", 0L);
            generation++;
            return true;
        }
    }

    /**
     * Compatibility entry point for callers from the old storage setting. It
     * now always preserves the current credential encrypted.
     */
    public boolean migrateToEncrypted() throws IOException {
        synchronized (lock) {
            String token = readTokenForRequest();
            if (token == null) return false;
            return saveToken(token);
        }
    }

    public TokenStatus tokenStatus() {
        synchronized (lock) {
            if (!hasCredential()) return new TokenStatus(Status.NOT_CONFIGURED, "", 0L);
            return status;
        }
    }

    void recordVerification(HighflyPremiumCatalogRepository.AccountInfo account) {
        if (account == null) return;
        synchronized (lock) {
            Status nextStatus = account.isUsable() ? Status.VALID
                    : account.isExpired() ? Status.EXPIRED : Status.INVALID;
            status = new TokenStatus(
                    nextStatus,
                    account.getPlanName(),
                    account.getExpiresAtMillis()
            );
            preferences.edit()
                    .putString(KEY_STATUS, nextStatus.name())
                    .putString(KEY_PLAN, account.getPlanName())
                    .putLong(KEY_EXPIRES_AT, account.getExpiresAtMillis())
                    .apply();
        }
    }

    void recordInvalid() {
        synchronized (lock) {
            if (!hasCredential()) {
                status = new TokenStatus(Status.NOT_CONFIGURED, "", 0L);
                return;
            }
            status = new TokenStatus(Status.INVALID, "", 0L);
            preferences.edit()
                    .putString(KEY_STATUS, Status.INVALID.name())
                    .remove(KEY_PLAN)
                    .remove(KEY_EXPIRES_AT)
                    .apply();
        }
    }

    public long getGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    /** Clears RAM copies; encrypted storage remains available for the next session. */
    public void clearSession() {
        synchronized (lock) {
            clearMemoryTokenLocked();
        }
    }

    public void clearToken() {
        synchronized (lock) {
            clearMemoryTokenLocked();
            clearEncryptedDataLocked();
            preferences.edit()
                    .remove(LEGACY_KEY_STORAGE_MODE)
                    .remove(KEY_STATUS)
                    .remove(KEY_PLAN)
                    .remove(KEY_EXPIRES_AT)
                    .commit();
            status = new TokenStatus(Status.NOT_CONFIGURED, "", 0L);
            generation++;
            try {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
            } catch (GeneralSecurityException | IOException ignored) {
                // The ciphertext has already been removed. A stale Keystore
                // key cannot decrypt anything without the removed IV/ciphertext.
            }
        }
    }

    private TokenStatus readStoredStatus() {
        String rawStatus = preferences.getString(KEY_STATUS, Status.UNKNOWN.name());
        Status parsed;
        try {
            parsed = Status.valueOf(rawStatus);
        } catch (IllegalArgumentException ignored) {
            parsed = Status.UNKNOWN;
        }
        return new TokenStatus(
                parsed,
                preferences.getString(KEY_PLAN, ""),
                preferences.getLong(KEY_EXPIRES_AT, 0L)
        );
    }

    private void replaceMemoryTokenLocked(String token) {
        clearMemoryTokenLocked();
        memoryToken = token.toCharArray();
    }

    private void clearMemoryTokenLocked() {
        if (memoryToken != null) Arrays.fill(memoryToken, '\0');
        memoryToken = null;
    }

    private void clearEncryptedDataLocked() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply();
    }

    private byte[][] encrypt(String token) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
        try {
            return new byte[][]{cipher.doFinal(plaintext), iv};
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private char[] decrypt(String ciphertext, String iv) throws GeneralSecurityException {
        byte[] encrypted = Base64.decode(ciphertext, Base64.DEFAULT);
        byte[] ivBytes = Base64.decode(iv, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, ivBytes));
        byte[] plaintext = cipher.doFinal(encrypted);
        try {
            String token = new String(plaintext, StandardCharsets.UTF_8);
            HighflyPremiumTokenRules.normalize(token);
            return token.toCharArray();
        } catch (IOException error) {
            throw new GeneralSecurityException("Credencial inválida.");
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(ivBytes, (byte) 0);
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
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((SecretKey) keyStore.getKey(KEY_ALIAS, null));
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
