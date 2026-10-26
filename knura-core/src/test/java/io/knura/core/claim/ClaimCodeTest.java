package io.knura.core.claim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
import io.knura.core.provision.TagKeySet;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Pins the claim code text and claim keys. Cards already printed depend on both, so these values
 * must never change. Expected values come from an independent Python implementation.
 */
class ClaimCodeTest {
    private static final byte[] SECRET = Hex.decode("000102030405060708090A0B0C0D0E0F");
    private static final byte[] UID = Hex.decode("04DE5F1EACC040");
    private static final String CODE = "0004-1061-050R-3GG2-8A1C-60T3-GFQS";

    @Test
    void formatsPinnedCode() {
        assertEquals(CODE, ClaimCode.of(SECRET).formatted());
    }

    @Test
    void derivesPinnedClaimKeys() {
        String[] expected = {
            "47E3CF909D6CADF51579A0D4674AB48B",
            "08AF2A84229D99693BB11D3735280F3A",
            "F3503E32CFD15485C011D7F4AB0A3DE0",
            "373BAEC6D0052D5AB037A4D2D518B497",
            "AB7C4E6E862B26D87844743A1126369E",
        };
        TagKeySet keys = ClaimCode.of(SECRET).keys(UID);
        for (int slot = 0; slot < expected.length; slot++) {
            assertArrayEquals(Hex.decode(expected[slot]), keys.key(slot), "key " + slot);
        }
    }

    @Test
    void readsCodesTheWayPeopleTypeThem() {
        assertArrayEquals(SECRET, ClaimCode.parse(CODE).secret());
        assertArrayEquals(SECRET, ClaimCode.parse(CODE.toLowerCase().replace("-", " ")).secret());
        assertArrayEquals(SECRET, ClaimCode.parse("  " + CODE.replace("-", "").replace('0', 'O').replace('1', 'l') + " ").secret());
    }

    @Test
    void readsTheQrLink() {
        ClaimCode claim = ClaimCode.of(SECRET);
        String link = claim.link(UID);
        assertEquals("knura://claim?uid=04DE5F1EACC040&code=00041061050R3GG28A1C60T3GFQS", link);
        assertArrayEquals(SECRET, ClaimCode.parse(link).secret());
        assertArrayEquals(UID, ClaimCode.linkUid(link));
        assertNull(ClaimCode.linkUid(CODE));
    }

    @Test
    void readsAPackCardLinkWithNoUid() {
        String pack = "knura://claim?code=00041061050R3GG28A1C60T3GFQS";
        assertArrayEquals(SECRET, ClaimCode.parse(pack).secret());
        assertNull(ClaimCode.linkUid(pack), "a pack card names no tag");
    }

    @Test
    void catchesTypos() {
        String typo = CODE.substring(0, 5) + (CODE.charAt(5) == '2' ? '3' : '2') + CODE.substring(6);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ClaimCode.parse(typo));
        assertTrue(e.getMessage().contains("typo"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ClaimCode.parse(CODE.substring(0, 20)));
        assertThrows(IllegalArgumentException.class, () -> ClaimCode.parse(CODE.replace('G', 'U')));
        assertThrows(IllegalArgumentException.class, () -> ClaimCode.parse("knura://claim?uid=04DE5F1EACC040"));
    }

    @Test
    void randomCodesRoundTrip() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 200; i++) {
            ClaimCode claim = ClaimCode.random(random);
            assertArrayEquals(claim.secret(), ClaimCode.parse(claim.formatted()).secret());
        }
    }

    @Test
    void cardOnlyOpensItsOwnTag() {
        byte[] other = UID.clone();
        other[6] ^= 1;
        assertFalse(Arrays.equals(ClaimCode.of(SECRET).keys(UID).key(0), ClaimCode.of(SECRET).keys(other).key(0)));
    }

    @Test
    void neverPrintsTheSecret() {
        assertFalse(ClaimCode.of(SECRET).toString().contains("0004"));
    }
}
