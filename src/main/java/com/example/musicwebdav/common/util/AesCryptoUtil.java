package com.example.musicwebdav.common.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesCryptoUtil {

    private static final int GCM_TAG_LENGTH_BIT = 128;
    private static final int GCM_IV_LENGTH_BYTE = 12;

    private AesCryptoUtil() {
    }

    public static String encrypt(String plainText, String key) {
        try {
            validateKey(key);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    public static String decrypt(String cipherText, String key) {
        try {
            validateKey(key);
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length < GCM_IV_LENGTH_BYTE) {
                throw new IllegalArgumentException("密文格式非法");
            }

            byte[] iv = new byte[GCM_IV_LENGTH_BYTE];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH_BYTE];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTE);
            System.arraycopy(combined, GCM_IV_LENGTH_BYTE, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            byte[] plainBytes = cipher.doFinal(encrypted);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密码解密失败", e);
        }
    }

    private static void validateKey(String key) {
        int keyLen = key == null ? 0 : key.length();
        if (keyLen != 16 && keyLen != 24 && keyLen != 32) {
            throw new IllegalArgumentException("AES key length must be 16/24/32");
        }
    }
}
