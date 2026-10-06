package io.knura.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class KeyDiversificationTest {
    private static final byte[] MASTER = Hex.decode("00112233445566778899AABBCCDDEEFF");

    /** NXP AN10922 section 2.2.1: UID 04782E21801D80, AID 3042F5, system identifier "NXP Abu". */
    @Test
    void matchesAn10922Example() {
        byte[] input = Hex.decode("04782E21801D80" + "3042F5" + "4E585020416275");
        assertArrayEquals(Hex.decode("A8DD63A3B89D54B37CA802473FDA9175"), KeyDiversification.aes128(MASTER, input));
    }

    /**
     * Inputs under 15 bytes take AN10922's two-block padding, which plain CMAC does not. Expected
     * values come from an independent Python implementation that also reproduces the NXP example.
     */
    @Test
    void padsShortInputToTwoBlocks() {
        byte[] input = {(byte) 0xAA};
        byte[] key = KeyDiversification.aes128(MASTER, input);
        assertArrayEquals(Hex.decode("4FAE8DC8568E60AF248701D356EFA7B6"), key);
        assertFalse(Arrays.equals(AesCmac.mac(MASTER, new byte[] {0x01, (byte) 0xAA}), key));
    }

    @Test
    void acceptsLongestInput() {
        byte[] input = new byte[31];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) i;
        }
        assertArrayEquals(Hex.decode("21C28CD89BB3147F66C7DBD4851CAB20"), KeyDiversification.aes128(MASTER, input));
    }

    @Test
    void rejectsBadInputLength() {
        assertThrows(IllegalArgumentException.class, () -> KeyDiversification.aes128(MASTER, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> KeyDiversification.aes128(MASTER, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> KeyDiversification.aes128(new byte[8], new byte[1]));
    }
}
