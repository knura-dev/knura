package io.knura.core.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class OwnerRecordTest {
    // RFC 8032's first Ed25519 test key. knura-resolver/test/vector.js checks the same record, so
    // what Java signs is what the resolver accepts.
    static final String SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    static final String PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    static final String OWNER = "rq5tw8np3a";
    static final String SERVER = "https://knurd.example.ts.net";
    static final long SEQ = 1759881600000L;
    static final String SIGNATURE = "ca378da0d9a347f7b12388edfdaddad62b3fe9f324915220783b669b49e7bfe8"
            + "2f26c3febdb7a24a9066846c248af8417321fd0836b6b738b95cecd0db45f405";

    @Test
    void signsTheSharedTestVector() {
        OwnerRecord record = OwnerRecord.sign(OWNER, SERVER, SEQ, OwnerKey.load(SEED, PUBLIC_KEY));
        assertEquals(new OwnerRecord(OWNER, SERVER, SEQ, PUBLIC_KEY, SIGNATURE), record);
        assertTrue(record.verify());
    }

    @Test
    void signatureCoversOwnerServerAndSeq() {
        assertFalse(new OwnerRecord("rq5tw8np3b", SERVER, SEQ, PUBLIC_KEY, SIGNATURE).verify());
        assertFalse(new OwnerRecord(OWNER, "https://evil.example.com", SEQ, PUBLIC_KEY, SIGNATURE).verify());
        assertFalse(new OwnerRecord(OWNER, SERVER, SEQ + 1, PUBLIC_KEY, SIGNATURE).verify());
        assertFalse(new OwnerRecord(OWNER, SERVER, SEQ, PUBLIC_KEY, "zz").verify());
        assertFalse(new OwnerRecord(OWNER, SERVER, SEQ, "00", SIGNATURE).verify());
    }

    @Test
    void generatedKeysSignAndReload() {
        OwnerKey key = OwnerKey.generate();
        OwnerKey reloaded = OwnerKey.load(key.seedHex(), key.publicKeyHex());
        assertTrue(OwnerRecord.sign(OWNER, SERVER, 1, reloaded).verify());
        assertTrue(reloaded.hasPublicKey(key.publicKeyHex()));
        assertFalse(reloaded.hasPublicKey(PUBLIC_KEY));
        assertFalse(reloaded.hasPublicKey("not hex"));
    }

    @Test
    void refusesMismatchedKeyHalves() {
        String otherPublic = OwnerKey.generate().publicKeyHex();
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.load(SEED, otherPublic));
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.load("abcd", PUBLIC_KEY));
    }

    @Test
    void jsonRoundTrip() {
        OwnerRecord record = new OwnerRecord(OWNER, SERVER, SEQ, PUBLIC_KEY, SIGNATURE);
        assertEquals(
                "{\"server\":\"https://knurd.example.ts.net\",\"seq\":1759881600000,\"publicKey\":\""
                        + PUBLIC_KEY + "\",\"signature\":\"" + SIGNATURE + "\"}",
                record.updateJson());
        String served = "{\"owner\":\"rq5tw8np3a\",\"server\":\"https:\\/\\/knurd.example.ts.net\",\"seq\":"
                + SEQ + ",\"publicKey\":\"" + PUBLIC_KEY + "\",\"signature\":\"" + SIGNATURE
                + "\",\"updatedAt\":\"2026-10-08T00:00:00.000Z\"}";
        assertEquals(record, OwnerRecord.fromJson(served));
        assertThrows(IllegalArgumentException.class, () -> OwnerRecord.fromJson("{\"owner\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> OwnerRecord.fromJson("{\"owner\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> OwnerRecord.fromJson("[]"));
    }

    @Test
    void checksServersLikeTheResolver() {
        for (String good : new String[] {SERVER, "https://knurd.example.com:8443", "https://example.com/knurd"}) {
            assertNull(OwnerRecord.checkServer(good, false), good);
        }
        for (String bad : new String[] {
            "http://knurd.example.ts.net",
            SERVER + "/",
            "https://1.2.3.4",
            "https://[::1]",
            "https://localhost",
            "https://printer.local",
            "https://user@example.com",
            "https://example.com?x=1",
            "https://example.com#x",
            "https://Example.com",
            "https://example.com:443",
            "https://exa mple.com",
            "javascript:alert(1)",
            "",
        }) {
            assertNotNull(OwnerRecord.checkServer(bad, false), bad);
        }
        assertNotNull(OwnerRecord.checkServer("http://127.0.0.1:8424", false));
        assertNull(OwnerRecord.checkServer("http://127.0.0.1:8424", true));
        assertNotNull(OwnerRecord.checkServer("http://example.com", true));
    }

    @Test
    void ownerIds() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 100; i++) {
            assertTrue(OwnerIds.isValid(OwnerIds.generate(random)));
        }
        assertTrue(OwnerIds.isValid(OWNER));
        for (String bad : new String[] {null, "rq5tw8np3", "rq5tw8np3aa", "Zk7hq4m2xa", "rq5tw8np3l", "rq5tw8np30"}) {
            assertFalse(OwnerIds.isValid(bad), bad);
        }
        assertEquals(32, OwnerIds.ALPHABET.chars().distinct().count());
    }
}
