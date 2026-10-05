package io.knura.core.sdm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.knura.core.crypto.Hex;
import io.knura.core.sdm.SdmVerificationException.Reason;
import org.junit.jupiter.api.Test;

/**
 * Checks against NXP AN12196 ("NTAG 424 DNA and NTAG 424 DNA TagTamper features and hints"),
 * section 3.4: SUN message with encrypted PICC data and CMAC, both keys all zero.
 *
 * <p>URL: {@code https://choose.url.com/ntag424?e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086}
 * decrypts to PICC data tag C7, UID 04DE5F1EACC040, read counter 0x00003D (61). SDMMACInputOffset
 * equals SDMMACOffset, so the MAC covers an empty input.
 */
class SdmVerifierTest {
    /** NXP's published example key. Real keys never appear in this repository. */
    private static final byte[] ZERO_KEY = new byte[16];

    private static final byte[] AN12196_PICC = Hex.decode("EF963FF7828658A599F3041510671E88");
    private static final byte[] AN12196_MAC = Hex.decode("94EED9EE65337086");
    private static final byte[] AN12196_UID = Hex.decode("04DE5F1EACC040");
    private static final int AN12196_COUNTER = 61;
    private static final byte[] EMPTY = new byte[0];

    @Test
    void decryptsAn12196PiccData() throws Exception {
        VerifiedTap picc = SdmVerifier.decryptPiccData(AN12196_PICC, ZERO_KEY);
        assertEquals("04DE5F1EACC040", picc.uidHex());
        assertEquals(AN12196_COUNTER, picc.readCounter());
    }

    @Test
    void computesAn12196Mac() {
        assertArrayEquals(
                AN12196_MAC, SdmVerifier.computeMac(AN12196_UID, AN12196_COUNTER, EMPTY, ZERO_KEY));
    }

    @Test
    void verifiesAn12196EncryptedTap() throws Exception {
        VerifiedTap tap =
                SdmVerifier.verifyEncrypted(AN12196_PICC, EMPTY, AN12196_MAC, ZERO_KEY, ZERO_KEY);
        assertEquals(new VerifiedTap(AN12196_UID, AN12196_COUNTER), tap);
    }

    @Test
    void verifiesSameTapInPlainMirrorMode() throws Exception {
        // Same UID, counter and key as AN12196, mirrored in clear: the MAC is identical because
        // it depends only on the session key and the (empty) MAC input.
        VerifiedTap tap =
                SdmVerifier.verifyPlain(AN12196_UID, AN12196_COUNTER, EMPTY, AN12196_MAC, ZERO_KEY);
        assertEquals("04DE5F1EACC040", tap.uidHex());
        assertEquals(AN12196_COUNTER, tap.readCounter());
    }

    @Test
    void rejectsTamperedMac() {
        byte[] mac = AN12196_MAC.clone();
        mac[7] ^= 0x01;
        assertReason(Reason.BAD_MAC, () -> SdmVerifier.verifyEncrypted(AN12196_PICC, EMPTY, mac, ZERO_KEY, ZERO_KEY));
    }

    @Test
    void rejectsChangedCounter() {
        assertReason(
                Reason.BAD_MAC,
                () -> SdmVerifier.verifyPlain(AN12196_UID, AN12196_COUNTER + 1, EMPTY, AN12196_MAC, ZERO_KEY));
    }

    @Test
    void rejectsWrongFileReadKey() {
        byte[] key = ZERO_KEY.clone();
        key[0] = 1;
        assertReason(
                Reason.BAD_MAC, () -> SdmVerifier.verifyEncrypted(AN12196_PICC, EMPTY, AN12196_MAC, ZERO_KEY, key));
    }

    @Test
    void rejectsMacOverDifferentInput() {
        byte[] input = "x".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertReason(
                Reason.BAD_MAC, () -> SdmVerifier.verifyPlain(AN12196_UID, AN12196_COUNTER, input, AN12196_MAC, ZERO_KEY));
    }

    @Test
    void wrongMetaReadKeyIsMalformed() {
        byte[] key = ZERO_KEY.clone();
        key[15] = 1;
        assertReason(
                Reason.MALFORMED, () -> SdmVerifier.verifyEncrypted(AN12196_PICC, EMPTY, AN12196_MAC, key, ZERO_KEY));
    }

    @Test
    void rejectsBadLengths() {
        assertReason(
                Reason.MALFORMED,
                () -> SdmVerifier.verifyEncrypted(new byte[15], EMPTY, AN12196_MAC, ZERO_KEY, ZERO_KEY));
        assertReason(
                Reason.MALFORMED, () -> SdmVerifier.verifyPlain(new byte[4], 1, EMPTY, AN12196_MAC, ZERO_KEY));
        assertReason(
                Reason.MALFORMED, () -> SdmVerifier.verifyPlain(AN12196_UID, 1, EMPTY, new byte[16], ZERO_KEY));
        assertReason(
                Reason.MALFORMED,
                () -> SdmVerifier.verifyPlain(AN12196_UID, 0x1000000, EMPTY, AN12196_MAC, ZERO_KEY));
    }

    @Test
    void verifiedTapCopiesUid() {
        byte[] uid = AN12196_UID.clone();
        VerifiedTap tap = new VerifiedTap(uid, 1);
        uid[0] = 0;
        tap.uid()[1] = 0;
        assertEquals("04DE5F1EACC040", tap.uidHex());
    }

    private interface Call {
        void run() throws SdmVerificationException;
    }

    private static void assertReason(Reason expected, Call call) {
        SdmVerificationException e = assertThrows(SdmVerificationException.class, call::run);
        assertEquals(expected, e.reason());
    }
}
