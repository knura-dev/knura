package io.knura.core.sdm;

import io.knura.core.crypto.AesCmac;
import io.knura.core.sdm.SdmVerificationException.Reason;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies NTAG 424 DNA Secure Dynamic Messaging (SUN) taps.
 *
 * <p>Two mirroring modes are supported:
 *
 * <ul>
 *   <li><b>Encrypted PICC data</b>: the URL carries 16 bytes of PICC data encrypted under
 *       SDMMetaReadKey, holding the UID and read counter, plus the CMAC.
 *   <li><b>Plain mirror</b>: the URL carries the UID and read counter in clear, plus the CMAC.
 * </ul>
 *
 * <p>In both modes the CMAC is computed with a session key derived from SDMFileReadKey, the UID
 * and the read counter (NT4H2421Gx datasheet section 9.3.9, NXP AN12196). {@code macInput} is
 * the bytes the tag MACs: the ASCII URL text from SDMMACInputOffset up to the MAC, which is
 * empty when the two offsets are equal.
 *
 * <p>All keys are 16-byte AES-128 keys. Replay protection (rejecting old counters) is the
 * caller's job; see {@link VerifiedTap}.
 */
public final class SdmVerifier {
    /** Length of a UID on NTAG 424 DNA. */
    public static final int UID_LENGTH = 7;
    /** Length of the transmitted (truncated) SDM MAC. */
    public static final int MAC_LENGTH = 8;
    /** Length of encrypted PICC data with UID and counter (one AES block). */
    public static final int PICC_DATA_LENGTH = 16;
    /** Largest value the 3-byte SDM read counter can hold. */
    public static final int MAX_READ_COUNTER = 0xFFFFFF;

    private static final byte UID_MIRRORED = (byte) 0x80;
    private static final byte COUNTER_MIRRORED = 0x40;
    private static final byte UID_LENGTH_MASK = 0x0F;

    private static final byte[] SV2_PREFIX = {0x3C, (byte) 0xC3, 0x00, 0x01, 0x00, (byte) 0x80};

    private SdmVerifier() {}

    /**
     * Decrypts PICC data and checks the CMAC.
     *
     * @param encryptedPiccData the 16 bytes from the URL's PICC data mirror
     * @param macInput the bytes covered by the MAC (often empty)
     * @param mac the 8-byte MAC from the URL
     * @param sdmMetaReadKey the key that encrypts the PICC data
     * @param sdmFileReadKey the key the SDM MAC session key derives from
     */
    public static VerifiedTap verifyEncrypted(
            byte[] encryptedPiccData,
            byte[] macInput,
            byte[] mac,
            byte[] sdmMetaReadKey,
            byte[] sdmFileReadKey)
            throws SdmVerificationException {
        VerifiedTap picc = decryptPiccData(encryptedPiccData, sdmMetaReadKey);
        return verifyPlain(picc.uid(), picc.readCounter(), macInput, mac, sdmFileReadKey);
    }

    /**
     * Checks the CMAC of a tap whose UID and read counter were mirrored in clear.
     *
     * @param uid the 7-byte UID
     * @param readCounter the SDM read counter, 0 to {@value #MAX_READ_COUNTER}
     * @param macInput the bytes covered by the MAC (often empty)
     * @param mac the 8-byte MAC from the URL
     * @param sdmFileReadKey the key the SDM MAC session key derives from
     */
    public static VerifiedTap verifyPlain(
            byte[] uid, int readCounter, byte[] macInput, byte[] mac, byte[] sdmFileReadKey)
            throws SdmVerificationException {
        if (uid.length != UID_LENGTH) {
            throw new SdmVerificationException(Reason.MALFORMED, "UID must be 7 bytes, got " + uid.length);
        }
        if (readCounter < 0 || readCounter > MAX_READ_COUNTER) {
            throw new SdmVerificationException(Reason.MALFORMED, "read counter out of range: " + readCounter);
        }
        if (mac.length != MAC_LENGTH) {
            throw new SdmVerificationException(Reason.MALFORMED, "MAC must be 8 bytes, got " + mac.length);
        }
        byte[] expected = computeMac(uid, readCounter, macInput, sdmFileReadKey);
        if (!MessageDigest.isEqual(expected, mac)) {
            throw new SdmVerificationException(Reason.BAD_MAC, "SDM MAC does not match");
        }
        return new VerifiedTap(uid, readCounter);
    }

    /**
     * Computes the 8-byte SDM MAC a genuine tag would send for this UID and counter. Exposed for
     * tests and for tooling that needs to predict a tag's output.
     */
    public static byte[] computeMac(byte[] uid, int readCounter, byte[] macInput, byte[] sdmFileReadKey) {
        byte[] sessionKey = AesCmac.mac(sdmFileReadKey, sessionVector(SV2_PREFIX, uid, readCounter));
        try {
            return AesCmac.truncate(AesCmac.mac(sessionKey, macInput));
        } finally {
            Arrays.fill(sessionKey, (byte) 0);
        }
    }

    /**
     * Decrypts 16 bytes of PICC data and returns the UID and read counter inside, without checking
     * the MAC. The result is not trustworthy on its own; it lets a caller look up the per-tag
     * SDMFileReadKey before calling {@link #verifyPlain}.
     */
    public static VerifiedTap decryptPiccData(byte[] encryptedPiccData, byte[] sdmMetaReadKey)
            throws SdmVerificationException {
        if (encryptedPiccData.length != PICC_DATA_LENGTH) {
            throw new SdmVerificationException(
                    Reason.MALFORMED, "PICC data must be 16 bytes, got " + encryptedPiccData.length);
        }
        byte[] plain = aesCbcDecryptZeroIv(sdmMetaReadKey, encryptedPiccData);
        try {
            byte tag = plain[0];
            if ((tag & UID_MIRRORED) == 0
                    || (tag & COUNTER_MIRRORED) == 0
                    || (tag & UID_LENGTH_MASK) != UID_LENGTH) {
                // Also the usual result of decrypting with the wrong SDMMetaReadKey.
                throw new SdmVerificationException(
                        Reason.MALFORMED,
                        String.format("unexpected PICC data tag 0x%02X (wrong key or unsupported layout)", tag));
            }
            byte[] uid = Arrays.copyOfRange(plain, 1, 1 + UID_LENGTH);
            int counter = (plain[8] & 0xFF) | (plain[9] & 0xFF) << 8 | (plain[10] & 0xFF) << 16;
            return new VerifiedTap(uid, counter);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Builds SV1/SV2: prefix || UID || read counter (LSB first). Exactly one AES block. */
    private static byte[] sessionVector(byte[] prefix, byte[] uid, int readCounter) {
        byte[] sv = new byte[AesCmac.BLOCK_SIZE];
        System.arraycopy(prefix, 0, sv, 0, prefix.length);
        System.arraycopy(uid, 0, sv, prefix.length, UID_LENGTH);
        int at = prefix.length + UID_LENGTH;
        sv[at] = (byte) readCounter;
        sv[at + 1] = (byte) (readCounter >>> 8);
        sv[at + 2] = (byte) (readCounter >>> 16);
        return sv;
    }

    private static byte[] aesCbcDecryptZeroIv(byte[] key, byte[] data) {
        if (key == null || key.length != AesCmac.BLOCK_SIZE) {
            throw new IllegalArgumentException("AES-128 key must be 16 bytes");
        }
        try {
            Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
            aes.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(new byte[AesCmac.BLOCK_SIZE]));
            return aes.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is unavailable in this JDK", e);
        }
    }
}
