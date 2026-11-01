package com.kartgen.hireflow.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for IMAP app passwords and OAuth credentials.
 * The key is supplied as 64 hex characters (32 bytes).
 */
public final class CryptoUtil {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private CryptoUtil() {}

    public static String encrypt(String plaintext, String hexKey) {
        try {
            byte[] keyBytes = keyBytes(hexKey);
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static String decrypt(String ciphertext, String hexKey) {
        try {
            byte[] keyBytes = keyBytes(hexKey);
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            if (raw.length <= IV_LEN) {
                throw new IllegalArgumentException("Ciphertext is too short");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public static byte[] keyBytes(String hexKey) {
        if (hexKey == null || !hexKey.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Crypto key must be exactly 64 hexadecimal characters (AES-256)");
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 64; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hexKey.charAt(i), 16) << 4)
                    + Character.digit(hexKey.charAt(i + 1), 16));
        }
        return out;
    }
}
