package io.knura.core.ntag424;

import io.knura.core.crypto.AesCmac;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * EV2 secure messaging state after AuthenticateEV2First: session keys, transaction identifier
 * and command counter (NT4H2421Gx datasheet section 9.1, NXP AN12196 section 4).
 */
final class SecureSession {
    private static final byte[] SV1_PREFIX = {(byte) 0xA5, 0x5A, 0x00, 0x01, 0x00, (byte) 0x80};
    private static final byte[] SV2_PREFIX = {0x5A, (byte) 0xA5, 0x00, 0x01, 0x00, (byte) 0x80};
    private static final byte[] COMMAND_IV_LABEL = {(byte) 0xA5, 0x5A};
    private static final byte[] RESPONSE_IV_LABEL = {0x5A, (byte) 0xA5};
    static final int TI_LENGTH = 4;

    private final byte[] encKey;
    private final byte[] macKey;
    private final byte[] ti;
    private int cmdCtr;

    SecureSession(byte[] authKey, byte[] rndA, byte[] rndB, byte[] ti) {
        byte[] context = sessionContext(rndA, rndB);
        this.encKey = AesCmac.mac(authKey, concat(SV1_PREFIX, context));
        this.macKey = AesCmac.mac(authKey, concat(SV2_PREFIX, context));
        this.ti = ti.clone();
    }

    /**
     * RndA[15..14] || (RndA[13..8] XOR RndB[15..10]) || RndB[9..0] || RndA[7..0], with byte 15
     * being the first byte sent.
     */
    private static byte[] sessionContext(byte[] rndA, byte[] rndB) {
        byte[] ctx = new byte[26];
        System.arraycopy(rndA, 0, ctx, 0, 2);
        for (int i = 0; i < 6; i++) {
            ctx[2 + i] = (byte) (rndA[2 + i] ^ rndB[i]);
        }
        System.arraycopy(rndB, 6, ctx, 8, 10);
        System.arraycopy(rndA, 8, ctx, 18, 8);
        return ctx;
    }

    /** MACt over {@code cmd || CmdCtr || TI || payload}, the 8 bytes appended to a command. */
    byte[] commandMac(int cmd, byte[] payload) {
        return mac(cmd, cmdCtr, payload);
    }

    /**
     * Checks the response MAC (over {@code status || CmdCtr+1 || TI || data}), then advances the
     * command counter.
     */
    void acceptResponse(int statusByte, byte[] data, byte[] responseMac, String command) throws Ntag424Exception {
        int next = (cmdCtr + 1) & 0xFFFF;
        if (!MessageDigest.isEqual(mac(statusByte, next, data), responseMac)) {
            throw new Ntag424Exception(command + ": response MAC does not match");
        }
        cmdCtr = next;
    }

    /** Advances the counter for a command sent in plain mode inside the session. */
    void acceptPlainResponse() {
        cmdCtr = (cmdCtr + 1) & 0xFFFF;
    }

    /** Encrypts command data, padding it ISO/IEC 9797-1 method 2 (always at least one byte). */
    byte[] encrypt(byte[] plain) {
        int len = (plain.length / AesCmac.BLOCK_SIZE + 1) * AesCmac.BLOCK_SIZE;
        byte[] padded = Arrays.copyOf(plain, len);
        padded[plain.length] = (byte) 0x80;
        try {
            return aesCbc(Cipher.ENCRYPT_MODE, iv(COMMAND_IV_LABEL, cmdCtr), padded);
        } finally {
            Arrays.fill(padded, (byte) 0);
        }
    }

    /**
     * Decrypts response data and strips the padding. Call after {@link #acceptResponse}: the
     * response IV uses the advanced counter.
     */
    byte[] decryptResponse(byte[] encrypted, String command) throws Ntag424Exception {
        if (encrypted.length == 0 || encrypted.length % AesCmac.BLOCK_SIZE != 0) {
            throw new Ntag424Exception(command + ": encrypted response has bad length " + encrypted.length);
        }
        byte[] plain = aesCbc(Cipher.DECRYPT_MODE, iv(RESPONSE_IV_LABEL, cmdCtr), encrypted);
        int end = plain.length - 1;
        while (end >= 0 && plain[end] == 0) {
            end--;
        }
        if (end < 0 || plain[end] != (byte) 0x80) {
            throw new Ntag424Exception(command + ": bad padding in encrypted response");
        }
        return Arrays.copyOf(plain, end);
    }

    void destroy() {
        Arrays.fill(encKey, (byte) 0);
        Arrays.fill(macKey, (byte) 0);
    }

    private byte[] mac(int first, int counter, byte[] payload) {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.write(first);
        in.write(counter);
        in.write(counter >>> 8);
        in.writeBytes(ti);
        in.writeBytes(payload);
        return AesCmac.truncate(AesCmac.mac(macKey, in.toByteArray()));
    }

    private byte[] iv(byte[] label, int counter) {
        byte[] block = new byte[AesCmac.BLOCK_SIZE];
        System.arraycopy(label, 0, block, 0, 2);
        System.arraycopy(ti, 0, block, 2, TI_LENGTH);
        block[6] = (byte) counter;
        block[7] = (byte) (counter >>> 8);
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"));
            return aes.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is unavailable in this JDK", e);
        }
    }

    private byte[] aesCbc(int mode, byte[] iv, byte[] data) {
        return aesCbc(mode, encKey, iv, data);
    }

    static byte[] aesCbc(int mode, byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
            aes.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return aes.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is unavailable in this JDK", e);
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
