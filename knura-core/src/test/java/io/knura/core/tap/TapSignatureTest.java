package io.knura.core.tap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TapSignatureTest {
    static final String QUERY = "e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086";

    static KeyPair p256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String sign(KeyPair keys, byte[] message) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(keys.getPrivate());
        s.update(message);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    @Test
    void aSignedTapChecksOutWithItsKey() throws Exception {
        KeyPair keys = p256();
        byte[] message = TapSignature.message(QUERY, "pixel", 1_800_000_000L, TapSignature.Kind.EVERYDAY);
        String sig = sign(keys, message);
        var key = TapSignature.parsePublicKey(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        assertTrue(TapSignature.verify(key, message, sig));
    }

    @Test
    void anyChangeToTheMessageBreaksTheSignature() throws Exception {
        KeyPair keys = p256();
        String sig = sign(keys, TapSignature.message(QUERY, "pixel", 100, TapSignature.Kind.EVERYDAY));
        var key = keys.getPublic();
        assertFalse(TapSignature.verify(key, TapSignature.message(QUERY, "other", 100, TapSignature.Kind.EVERYDAY), sig));
        assertFalse(TapSignature.verify(key, TapSignature.message(QUERY, "pixel", 101, TapSignature.Kind.EVERYDAY), sig));
        assertFalse(TapSignature.verify(key, TapSignature.message(QUERY, "pixel", 100, TapSignature.Kind.CONFIRM), sig));
        assertFalse(TapSignature.verify(key, TapSignature.message(QUERY + "0", "pixel", 100, TapSignature.Kind.EVERYDAY), sig));
        assertFalse(TapSignature.verify(p256().getPublic(),
                TapSignature.message(QUERY, "pixel", 100, TapSignature.Kind.EVERYDAY), sig));
    }

    @Test
    void junkSignaturesAreFalseNotErrors() throws Exception {
        var key = p256().getPublic();
        byte[] message = TapSignature.message(QUERY, "pixel", 100, TapSignature.Kind.EVERYDAY);
        assertFalse(TapSignature.verify(key, message, null));
        assertFalse(TapSignature.verify(key, message, "not base64!"));
        assertFalse(TapSignature.verify(key, message, "AAAA"));
    }

    @Test
    void onlyP256KeysAreAccepted() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp384r1"));
        String p384 = Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
        assertThrows(IllegalArgumentException.class, () -> TapSignature.parsePublicKey(p384));
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        String rsaKey = Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());
        assertThrows(IllegalArgumentException.class, () -> TapSignature.parsePublicKey(rsaKey));
        assertThrows(IllegalArgumentException.class, () -> TapSignature.parsePublicKey("junk"));
        assertThrows(IllegalArgumentException.class, () -> TapSignature.parsePublicKey(null));
    }

    @Test
    void timeMustBeWithinAMinute() {
        Instant now = Instant.ofEpochSecond(1_000_000);
        assertTrue(TapSignature.fresh(1_000_000, now));
        assertTrue(TapSignature.fresh(1_000_060, now));
        assertTrue(TapSignature.fresh(999_940, now));
        assertFalse(TapSignature.fresh(1_000_061, now));
        assertFalse(TapSignature.fresh(999_939, now));
    }

    @Test
    void queriesMustBeOneShortAsciiLine() {
        assertTrue(TapSignature.saneQuery(QUERY));
        assertFalse(TapSignature.saneQuery(""));
        assertFalse(TapSignature.saneQuery(null));
        assertFalse(TapSignature.saneQuery("e=1\nc=2"));
        assertFalse(TapSignature.saneQuery("e=1 c=2"));
        assertFalse(TapSignature.saneQuery("e=é"));
        assertFalse(TapSignature.saneQuery("e=" + "0".repeat(600)));
    }

    @Test
    void kindsHaveStableNames() {
        assertEquals(TapSignature.Kind.EVERYDAY, TapSignature.Kind.of("everyday"));
        assertEquals(TapSignature.Kind.CONFIRM, TapSignature.Kind.of("confirm"));
        assertNull(TapSignature.Kind.of("admin"));
    }
}
