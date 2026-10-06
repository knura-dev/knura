package io.knura.core.keys;

import io.knura.core.crypto.KeyDiversification;
import io.knura.core.sdm.SdmVerifier;
import java.nio.charset.StandardCharsets;

/**
 * Knura's key scheme: every NTAG 424 DNA key comes from one master key with AN10922
 * diversification, so a server holding the master key can work out any tag's keys without a
 * per-tag key database, and copying one tag's keys exposes only that tag.
 *
 * <p>Diversification input, after AN10922's leading {@code 0x01}:
 *
 * <ul>
 *   <li>Per-tag key: {@code "Knura" || 0x01 || keyNo || UID} (14 bytes).
 *   <li>Fleet key: {@code "Knura" || 0x02 || keyNo} (7 bytes), the same for every tag.
 * </ul>
 *
 * <p>SDMMetaReadKey is a fleet key because the verifier needs it to decrypt the PICC data before
 * it knows the UID. That key only hides the UID and counter; forging a tap needs the per-tag
 * SDMFileReadKey. Changing this scheme changes every derived key, so provisioned tags would stop
 * verifying: treat it as fixed.
 */
public final class TagKeyDerivation {
    /** Application master key slot: authenticates key and settings changes. */
    public static final int APP_MASTER_KEY = 0;
    /** SDMMetaReadKey slot: encrypts the PICC data (UID and counter) in the tap URL. */
    public static final int SDM_META_READ_KEY = 1;
    /** SDMFileReadKey slot: the key the tap MAC is derived from. */
    public static final int SDM_FILE_READ_KEY = 2;
    /** NTAG 424 DNA has key slots 0 to 4. */
    public static final int KEY_SLOTS = 5;

    private static final byte[] SYSTEM_ID = "Knura".getBytes(StandardCharsets.US_ASCII);
    private static final byte PER_TAG = 0x01;
    private static final byte FLEET = 0x02;

    private TagKeyDerivation() {}

    /** The key for slot {@code keyNo} of the tag with this 7-byte {@code uid}. */
    public static byte[] tagKey(byte[] masterKey, byte[] uid, int keyNo) {
        if (uid.length != SdmVerifier.UID_LENGTH) {
            throw new IllegalArgumentException("UID must be 7 bytes, got " + uid.length);
        }
        byte[] input = header(PER_TAG, keyNo, uid.length);
        System.arraycopy(uid, 0, input, SYSTEM_ID.length + 2, uid.length);
        return KeyDiversification.aes128(masterKey, input);
    }

    /** The key for slot {@code keyNo} shared by every tag under this master key. */
    public static byte[] fleetKey(byte[] masterKey, int keyNo) {
        return KeyDiversification.aes128(masterKey, header(FLEET, keyNo, 0));
    }

    private static byte[] header(byte purpose, int keyNo, int extra) {
        if (keyNo < 0 || keyNo >= KEY_SLOTS) {
            throw new IllegalArgumentException("key number must be 0 to 4, got " + keyNo);
        }
        byte[] input = new byte[SYSTEM_ID.length + 2 + extra];
        System.arraycopy(SYSTEM_ID, 0, input, 0, SYSTEM_ID.length);
        input[SYSTEM_ID.length] = purpose;
        input[SYSTEM_ID.length + 1] = (byte) keyNo;
        return input;
    }
}
