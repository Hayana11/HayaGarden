package xyz.lovestyle.home.canary;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-scoped health credential storage.
 *
 * The install id and device id are non-secret metadata. The credential is
 * encrypted with an AndroidKeyStore AES/GCM key and is never exposed through
 * a JavaScript getter or diagnostic payload.
 */
public final class HealthCredentialStore {
    private static final String PREFS = "elpis_health_device";
    private static final String KEY_ALIAS = "elpis_health_device_v1";
    private static final String INSTALL_ID = "install_id";
    private static final String DEVICE_ID = "device_id";
    private static final String CIPHERTEXT = "credential_ciphertext";
    private static final String IV = "credential_iv";
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_DEVICE_ID_LENGTH = 80;
    private static final int MAX_CREDENTIAL_LENGTH = 512;

    private final SharedPreferences preferences;
    private final SecureRandom random = new SecureRandom();

    public HealthCredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized String getInstallId() {
        String existing = preferences.getString(INSTALL_ID, "");
        if (existing != null && !existing.isEmpty()) return existing;
        String generated = UUID.randomUUID().toString();
        preferences.edit().putString(INSTALL_ID, generated).apply();
        return generated;
    }

    public synchronized boolean provision(String deviceId, String credential) {
        if (!validDeviceId(deviceId) || !validCredential(credential)) return false;
        try {
            SecretKey key = getOrCreateKey();
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(credential.getBytes(StandardCharsets.UTF_8));
            preferences.edit()
                    .putString(DEVICE_ID, deviceId.trim())
                    .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized String getDeviceId() {
        String credential = getCredential();
        if (credential == null) return "";
        String deviceId = preferences.getString(DEVICE_ID, "");
        return deviceId == null ? "" : deviceId;
    }

    public synchronized String getCredential() {
        String encodedCiphertext = preferences.getString(CIPHERTEXT, "");
        String encodedIv = preferences.getString(IV, "");
        if (encodedCiphertext == null || encodedCiphertext.isEmpty()
                || encodedIv == null || encodedIv.isEmpty()) {
            return null;
        }
        try {
            SecretKey key = getOrCreateKey();
            byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.DEFAULT);
            byte[] iv = Base64.decode(encodedIv, Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized JSONObject getEnrollmentState() {
        JSONObject result = new JSONObject();
        try {
            String credential = getCredential();
            String deviceId = getDeviceId();
            boolean configured = credential != null && !credential.isEmpty() && !deviceId.isEmpty();
            result.put("configured", configured);
            result.put("deviceId", configured ? deviceId : JSONObject.NULL);
        } catch (Exception ignored) {
            try {
                result.put("configured", false);
                result.put("deviceId", JSONObject.NULL);
            } catch (Exception ignoredAgain) {}
        }
        return result;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private static boolean validDeviceId(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_DEVICE_ID_LENGTH) return false;
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean validCredential(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= MAX_CREDENTIAL_LENGTH;
    }
}
