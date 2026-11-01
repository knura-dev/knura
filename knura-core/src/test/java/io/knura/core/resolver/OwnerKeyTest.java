package io.knura.core.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OwnerKeyTest {
    @Test
    void fromSeedRebuildsThePublicKey() {
        for (int i = 0; i < 5; i++) {
            OwnerKey key = OwnerKey.generate();
            OwnerKey back = OwnerKey.fromSeed(key.seedHex());
            assertEquals(key.publicKeyHex(), back.publicKeyHex());
        }
    }

    @Test
    void fromSeedMatchesRfc8032() {
        // RFC 8032 section 7.1, TEST 1.
        OwnerKey key = OwnerKey.fromSeed("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", key.publicKeyHex());
    }

    @Test
    void fromSeedRejectsBadSeeds() {
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.fromSeed("abcd"));
    }
}
