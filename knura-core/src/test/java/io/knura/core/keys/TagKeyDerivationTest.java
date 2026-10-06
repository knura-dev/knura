package io.knura.core.keys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.knura.core.crypto.Hex;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Pins Knura's derived keys. These values must never change: tags provisioned with them would
 * stop verifying. Expected values come from an independent Python AN10922 implementation.
 */
class TagKeyDerivationTest {
    private static final byte[] MASTER = Hex.decode("00112233445566778899AABBCCDDEEFF");
    private static final byte[] UID = Hex.decode("04DE5F1EACC040");

    @Test
    void derivesPinnedTagKeys() {
        String[] expected = {
            "B47034E559E557F9B9A9AC4600D02F3C",
            "FD4D086C127811F8AEDAC7DD1C84B3D3",
            "D3D118FEC036256A73FE0BBE36FFF021",
            "6B0663132AC25281BE8EA200457898A8",
            "FBDDFC8507701EFDDA4A85FEDDA9D9B7",
        };
        for (int keyNo = 0; keyNo < expected.length; keyNo++) {
            assertArrayEquals(Hex.decode(expected[keyNo]), TagKeyDerivation.tagKey(MASTER, UID, keyNo));
        }
    }

    @Test
    void derivesPinnedFleetKey() {
        assertArrayEquals(
                Hex.decode("D23924B4018F2BC86493E513E09B9692"),
                TagKeyDerivation.fleetKey(MASTER, TagKeyDerivation.SDM_META_READ_KEY));
    }

    @Test
    void differentTagsGetDifferentKeys() {
        byte[] other = UID.clone();
        other[6] ^= 1;
        assertFalse(Arrays.equals(
                TagKeyDerivation.tagKey(MASTER, UID, TagKeyDerivation.SDM_FILE_READ_KEY),
                TagKeyDerivation.tagKey(MASTER, other, TagKeyDerivation.SDM_FILE_READ_KEY)));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> TagKeyDerivation.tagKey(MASTER, new byte[4], 2));
        assertThrows(IllegalArgumentException.class, () -> TagKeyDerivation.tagKey(MASTER, UID, 5));
        assertThrows(IllegalArgumentException.class, () -> TagKeyDerivation.fleetKey(MASTER, -1));
    }
}
