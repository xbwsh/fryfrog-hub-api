package com.fryfrog.hub.common.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Subsonic 密码 AES-256-GCM 加解密工具。
 * <p>
 * 密钥从环境变量 {@code SUBSONIC_ENCRYPT_KEY} 读取（Base64 编码的 32 字节）。
 * 未配置时回退明文存储（兼容旧数据）。
 */
@Slf4j
public class SubsonicPasswordEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String ENCRYPTED_PREFIX = "enc2:";

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public SubsonicPasswordEncryptor(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.keySpec = null;
            log.info("Subsonic password encryption disabled (no key configured)");
        } else {
            byte[] key = Base64.getDecoder().decode(base64Key);
            if (key.length != 32) {
                throw new IllegalArgumentException("SUBSONIC_ENCRYPT_KEY must be a Base64-encoded 32-byte key");
            }
            this.keySpec = new SecretKeySpec(key, "AES");
            log.info("Subsonic password encryption enabled (AES-256-GCM)");
        }
    }

    /** 加密明文密码；未配置密钥时返回明文。 */
    public String encrypt(String plainText) {
        if (plainText == null || keySpec == null) {
            return plainText;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("Failed to encrypt subsonic password, storing plaintext", e);
            return plainText;
        }
    }

    /** 解密密文；未配置密钥或非加密格式时原样返回。 */
    public String decrypt(String cipherText) {
        if (cipherText == null || keySpec == null || !cipherText.startsWith(ENCRYPTED_PREFIX)) {
            return cipherText;
        }
        try {
            String payload = cipherText.substring(ENCRYPTED_PREFIX.length());
            String[] parts = payload.split(":", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt subsonic password", e);
            return cipherText;
        }
    }

    /** 是否已配置加密密钥 */
    public boolean isEncryptionEnabled() {
        return keySpec != null;
    }
}
