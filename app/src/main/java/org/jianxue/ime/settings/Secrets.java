package org.jianxue.ime.settings;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** API 密钥由 Android Keystore 的 AES-GCM 密钥加密，禁止明文落盘。 */
public final class Secrets {
    private static final String ALIAS = "jianxue-cloud";
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static void save(Context context, String value) throws Exception {
        saveLocal(context, "secret", value);
    }
    public static void saveLocal(Context context, String name, String value) throws Exception {
        android.content.SharedPreferences.Editor editor = Preferences.store(context).edit();
        if (value.isEmpty()) { editor.remove(name).remove(name + "_iv").apply(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        editor.putString(name, Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
                .putString(name + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
    }
    static String read(Context context) {
        return readLocal(context, "secret");
    }
    public static String readLocal(Context context, String name) {
        try {
            android.content.SharedPreferences preferences = Preferences.store(context);
            String encoded = preferences.getString(name, "");
            if (encoded.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(preferences.getString(name + "_iv", ""), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception unavailable) { return ""; }
    }
}
