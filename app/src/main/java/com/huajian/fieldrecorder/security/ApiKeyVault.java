package com.huajian.fieldrecorder.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the user-entered API key out of the APK, session logs and export packets. */
public final class ApiKeyVault {
    private static final String ALIAS = "huajian.dashscope.api_key.aes.v1";
    private static final String PREFS = "qwen3_private_settings";
    private static final String CIPHERTEXT = "encrypted_api_key";
    private static final String REGION = "region";
    private static final String WORKSPACE = "workspace_id";

    private ApiKeyVault() { }

    private static SecretKey aesKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context ctx, String apiKey, String region, String workspace) throws Exception {
        String clean = apiKey == null ? "" : apiKey.trim();
        if (clean.length() < 12 || clean.length() > 512 || clean.contains("\n") || clean.contains("\r"))
            throw new IllegalArgumentException("API Key 长度或格式无效");
        validateWorkspace(workspace);
        if (!"beijing".equals(region) && !"singapore".equals(region))
            throw new IllegalArgumentException("未知的 DashScope 地域");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new SecureRandom());
        byte[] bytes = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(bytes, Base64.NO_WRAP);
        if (!prefs(ctx).edit().putString(CIPHERTEXT, packed).putString(REGION, region)
                .putString(WORKSPACE, workspace == null ? "" : workspace.trim()).commit())
            throw new IllegalStateException("API Key 未能安全写入本地存储");
    }

    public static String load(Context ctx) throws Exception {
        String packed = prefs(ctx).getString(CIPHERTEXT, "");
        if (packed.isEmpty()) return "";
        String[] parts = packed.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("加密的 API Key 已损坏，请重新填写");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    public static boolean hasKey(Context ctx) {
        return !prefs(ctx).getString(CIPHERTEXT, "").isEmpty();
    }
    public static String region(Context ctx) { return prefs(ctx).getString(REGION, "beijing"); }
    public static String workspace(Context ctx) { return prefs(ctx).getString(WORKSPACE, ""); }
    public static void clear(Context ctx) { prefs(ctx).edit().remove(CIPHERTEXT).apply(); }

    public static void validateWorkspace(String raw) {
        if (raw == null || raw.trim().isEmpty()) return; // Legacy public endpoint is an allowed fallback.
        if (!raw.matches("[A-Za-z0-9-]{1,100}"))
            throw new IllegalArgumentException("Workspace ID 只允许英文字母、数字和连字符");
    }
}
