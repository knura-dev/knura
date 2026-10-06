package io.knura.core.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128 CMAC (NIST SP 800-38B, RFC 4493) built on the JDK's AES block cipher.
 *
 * <p>NTAG 424 DNA uses CMAC both to derive session keys and to authenticate SDM taps.
 */
public final class AesCmac {
    public static final int BLOCK_SIZE = 16;
    private static final byte RB = (byte) 0x87;

    private AesCmac() {}

    /** Returns the full 16-byte CMAC of {@code message} under the 16-byte AES {@code key}. */
    public static byte[] mac(byte[] key, byte[] message) {
        return mac(key, message, 1);
    }

    /**
     * CMAC that pads {@code message} to at least {@code minBlocks} blocks. With {@code minBlocks}
     * of 1 this is plain CMAC; AN10922 key diversification pads to 2 blocks.
     */
    static byte[] mac(byte[] key, byte[] message, int minBlocks) {
        requireKey(key);
        Cipher aes = ecbEncryptor(key);
        byte[] k1 = subkey(aes.update(new byte[BLOCK_SIZE]));
        byte[] k2 = subkey(k1);

        int blocks = Math.max(minBlocks, (message.length + BLOCK_SIZE - 1) / BLOCK_SIZE);
        byte[] padded = Arrays.copyOf(message, blocks * BLOCK_SIZE);
        boolean complete = message.length == padded.length;
        if (!complete) {
            padded[message.length] = (byte) 0x80;
        }

        byte[] x = new byte[BLOCK_SIZE];
        byte[] y = new byte[BLOCK_SIZE];
        for (int i = 0; i < blocks; i++) {
            for (int j = 0; j < BLOCK_SIZE; j++) {
                y[j] = (byte) (x[j] ^ padded[i * BLOCK_SIZE + j]);
            }
            if (i == blocks - 1) {
                xorInto(y, complete ? k1 : k2);
            }
            x = aes.update(y);
        }
        return x;
    }

    /**
     * Truncates a 16-byte CMAC to the 8 bytes NTAG 424 DNA transmits: the bytes at odd
     * indices (1, 3, ..., 15), per the NT4H2421Gx datasheet.
     */
    public static byte[] truncate(byte[] fullMac) {
        if (fullMac.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("CMAC must be 16 bytes, got " + fullMac.length);
        }
        byte[] out = new byte[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = fullMac[2 * i + 1];
        }
        return out;
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("AES-128 key must be 16 bytes");
        }
    }

    private static Cipher ecbEncryptor(byte[] key) {
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return aes;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is unavailable in this JDK", e);
        }
    }

    private static byte[] subkey(byte[] in) {
        byte[] out = new byte[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            int next = i + 1 < BLOCK_SIZE ? (in[i + 1] & 0xFF) >>> 7 : 0;
            out[i] = (byte) ((in[i] << 1) | next);
        }
        if ((in[0] & 0x80) != 0) {
            out[BLOCK_SIZE - 1] ^= RB;
        }
        return out;
    }

    private static void xorInto(byte[] target, byte[] other) {
        for (int i = 0; i < target.length; i++) {
            target[i] ^= other[i];
        }
    }
}
