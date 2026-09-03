package com.fryfrog.hub.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubsonicPasswordEncryptorTest {

    @Test
    void encryptDecryptRoundTrip() {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        SubsonicPasswordEncryptor enc = new SubsonicPasswordEncryptor(key);

        String plain = "my-secret-password-123";
        String encrypted = enc.encrypt(plain);

        assertNotEquals(plain, encrypted);
        assertTrue(encrypted.startsWith("enc2:"));

        String decrypted = enc.decrypt(encrypted);
        assertEquals(plain, decrypted);
    }

    @Test
    void decryptPassthroughWhenNoKey() {
        SubsonicPasswordEncryptor enc = new SubsonicPasswordEncryptor(null);

        String plain = "plaintext-password";
        assertEquals(plain, enc.encrypt(plain));
        assertEquals(plain, enc.decrypt(plain));
    }

    @Test
    void decryptPassthroughForLegacyFormat() {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        SubsonicPasswordEncryptor enc = new SubsonicPasswordEncryptor(key);

        String legacy = "old-plaintext-password";
        assertEquals(legacy, enc.decrypt(legacy));
    }

    @Test
    void differentInputsProduceDifferentCiphertexts() {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        SubsonicPasswordEncryptor enc = new SubsonicPasswordEncryptor(key);

        String enc1 = enc.encrypt("password");
        String enc2 = enc.encrypt("password");
        assertNotEquals(enc1, enc2);
    }

    @Test
    void nullInputReturnsNull() {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        SubsonicPasswordEncryptor enc = new SubsonicPasswordEncryptor(key);

        assertNull(enc.encrypt(null));
        assertNull(enc.decrypt(null));
    }
}
