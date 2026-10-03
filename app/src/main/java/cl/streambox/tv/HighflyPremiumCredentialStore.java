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
 * Token de Highfly Premium cifrado con AES-GCM; la clave vive en Android Keystore y
 * nunca sale del dispositivo (allowBackup=false). La UI solo ve el estado (plan y
 * vigencia), jamás el token. Restaurado y simplificado el 2026-10-03.
 */
final class HighflyPremiumCredentialStore implements HighflyPremiumLink.Source {
    private static final String KEY_ALIAS = "vibem3u_highfly_premium";
    private static final String KEY_CIPHERTEXT = "highfly_premium_token_ciphertext";
    private static final String KEY_IV = "highfly_premium_token_iv";
    private static final String KEY_STATUS = "highfly_premium_status";
    private static final String KEY_PLAN = "highfly_premium_plan";
    private static final String KEY_EXPIRES_AT = "highfly_premium_expires_at";
    private static final String KEY_VERIFIED_AT = "highfly_premium_verified_at";
    private static final String KEY_REGION = "highfly_premium_region";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    enum Status { NOT_CONFIGURED, VALID, REJECTED }

    /** Estado visible para la UI; no contiene el token. */
    static final class State {
        final Status status;
        final HighflyPremiumAccount.Plan plan;
        final long expiresAtMillis;
        final long verifiedAtMillis;

        State(Status status, HighflyPremiumAccount.Plan plan, long expiresAtMillis, long verifiedAtMillis) {
            this.status = status;
            this.plan = plan;
            this.expiresAtMillis = expiresAtMillis;
            this.verifiedAtMillis = verifiedAtMillis;
        }
    }

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile HighflyPremiumCredentialStore instance;

    private final SharedPreferences preferences;
    private final Object lock = new Object();
    private char[] memoryToken;

    private HighflyPremiumCredentialStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
    }

    static HighflyPremiumCredentialStore getInstance(Context context) {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new HighflyPremiumCredentialStore(context);
                    HighflyPremiumLink.install(instance);
                }
            }
        }
        return instance;
    }

    /** La instancia ya creada por una Activity, o null. */
    static HighflyPremiumCredentialStore peek() {
        return instance;
    }

    @Override public boolean linked() {
        return hasCredential();
    }

    @Override public boolean rejected() {
        return state().status == Status.REJECTED;
    }

    @Override public String token() {
        return readTokenForRequest();
    }

    @Override public void markRejected() {
        recordRejected();
    }

    boolean hasCredential() {
        synchronized (lock) {
            if (memoryToken != null && memoryToken.length > 0) return true;
            return !AppStrings.isBlank(preferences.getString(KEY_CIPHERTEXT, ""))
                    && !AppStrings.isBlank(preferences.getString(KEY_IV, ""));
        }
    }

    /** Copia breve para armar una solicitud; nunca para mostrar. */
    String readTokenForRequest() {
        synchronized (lock) {
            if (memoryToken != null && memoryToken.length > 0) return new String(memoryToken);
            String ciphertext = preferences.getString(KEY_CIPHERTEXT, "");
            String iv = preferences.getString(KEY_IV, "");
            if (AppStrings.isBlank(ciphertext) || AppStrings.isBlank(iv)) return null;
            try {
                memoryToken = decrypt(ciphertext, iv);
                return new String(memoryToken);
            } catch (GeneralSecurityException | IllegalArgumentException error) {
                // Una falla pasajera del Keystore no borra la única credencial guardada.
                return null;
            }
        }
    }

    /** Guarda un token ya verificado junto con su plan. */
    void saveVerified(String value, HighflyPremiumAccount account, HighflyPremiumRegion region)
            throws IOException {
        String token = HighflyPremiumTokenRules.normalize(value);
        synchronized (lock) {
            try {
                byte[][] encrypted = encrypt(token);
                SharedPreferences.Editor editor = preferences.edit()
                        .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted[0], Base64.NO_WRAP))
                        .putString(KEY_IV, Base64.encodeToString(encrypted[1], Base64.NO_WRAP));
                if (region != null) editor.putString(KEY_REGION, region.preferenceValue());
                if (!editor.commit()) throw new IOException("No se pudo guardar la credencial.");
            } catch (GeneralSecurityException error) {
                throw new IOException("No se pudo proteger la credencial.", error);
            }
            clearMemoryTokenLocked();
            memoryToken = token.toCharArray();
            recordVerificationLocked(account);
        }
    }

    void recordVerification(HighflyPremiumAccount account) {
        synchronized (lock) {
            recordVerificationLocked(account);
        }
    }

    /** Highfly rechazó el token (401/403 o cuenta inactiva): vencido o revocado. */
    void recordRejected() {
        synchronized (lock) {
            if (!hasCredential()) return;
            preferences.edit().putString(KEY_STATUS, Status.REJECTED.name()).apply();
        }
    }

    State state() {
        synchronized (lock) {
            if (!hasCredential()) {
                return new State(Status.NOT_CONFIGURED, HighflyPremiumAccount.Plan.PREMIUM, 0L, 0L);
            }
            Status status;
            try {
                status = Status.valueOf(preferences.getString(KEY_STATUS, Status.VALID.name()));
            } catch (IllegalArgumentException ignored) {
                status = Status.VALID;
            }
            HighflyPremiumAccount.Plan plan;
            try {
                plan = HighflyPremiumAccount.Plan.valueOf(preferences.getString(KEY_PLAN, "PREMIUM"));
            } catch (IllegalArgumentException ignored) {
                plan = HighflyPremiumAccount.Plan.PREMIUM;
            }
            return new State(status, plan,
                    preferences.getLong(KEY_EXPIRES_AT, 0L),
                    preferences.getLong(KEY_VERIFIED_AT, 0L));
        }
    }

    boolean isUsable() {
        return state().status == Status.VALID;
    }

    @Override public HighflyPremiumRegion region() {
        return HighflyPremiumRegion.fromPreference(preferences.getString(KEY_REGION, "auto"));
    }

    void setRegion(HighflyPremiumRegion region) {
        preferences.edit().putString(KEY_REGION,
                (region == null ? HighflyPremiumRegion.AUTO : region).preferenceValue()).apply();
    }

    /** Borra el token y su estado. La clave del Keystore queda inerte y se reutiliza. */
    void clear() {
        synchronized (lock) {
            clearMemoryTokenLocked();
            preferences.edit()
                    .remove(KEY_CIPHERTEXT)
                    .remove(KEY_IV)
                    .remove(KEY_STATUS)
                    .remove(KEY_PLAN)
                    .remove(KEY_EXPIRES_AT)
                    .remove(KEY_VERIFIED_AT)
                    .commit();
        }
    }

    /** Libera la copia en RAM; el cifrado queda para la próxima sesión. */
    void clearSession() {
        synchronized (lock) {
            clearMemoryTokenLocked();
        }
    }

    private void recordVerificationLocked(HighflyPremiumAccount account) {
        if (account == null) return;
        long now = System.currentTimeMillis();
        Status status = account.isActive() && !account.isExpired(now) ? Status.VALID : Status.REJECTED;
        preferences.edit()
                .putString(KEY_STATUS, status.name())
                .putString(KEY_PLAN, account.getPlan().name())
                .putLong(KEY_EXPIRES_AT, account.getExpiresAtMillis())
                .putLong(KEY_VERIFIED_AT, now)
                .apply();
    }

    private void clearMemoryTokenLocked() {
        if (memoryToken != null) Arrays.fill(memoryToken, '\0');
        memoryToken = null;
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

    private char[] decrypt(String ciphertext, String iv) throws GeneralSecurityException {
        byte[] encrypted = Base64.decode(ciphertext, Base64.DEFAULT);
        byte[] ivBytes = Base64.decode(iv, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, ivBytes));
        byte[] plaintext = cipher.doFinal(encrypted);
        try {
            String token = new String(plaintext, StandardCharsets.UTF_8);
            if (!HighflyPremiumTokenRules.isValid(token)) {
                throw new GeneralSecurityException("Credencial inválida.");
            }
            return token.toCharArray();
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
