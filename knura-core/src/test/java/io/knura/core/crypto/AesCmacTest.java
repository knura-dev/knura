package io.knura.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Test vectors from RFC 4493 section 4 (AES-128). */
class AesCmacTest {
    private static final byte[] KEY = Hex.decode("2B7E151628AED2A6ABF7158809CF4F3C");
    private static final String M64 =
            "6BC1BEE22E409F96E93D7E117393172A"
                    + "AE2D8A571E03AC9C9EB76FAC45AF8E51"
                    + "30C81C46A35CE411E5FBC1191A0A52EF"
                    + "F69F2445DF4F9B17AD2B417BE66C3710";

    @Test
    void emptyMessage() {
        assertMac("BB1D6929E95937287FA37D129B756746", "");
    }

    @Test
    void oneFullBlock() {
        assertMac("070A16B46B4D4144F79BDD9DD04A287C", M64.substring(0, 32));
    }

    @Test
    void partialFinalBlock() {
        assertMac("DFA66747DE9AE63030CA32611497C827", M64.substring(0, 80));
    }

    @Test
    void fourFullBlocks() {
        assertMac("51F0BEBF7E3B9D92FC49741779363CFE", M64);
    }

    @Test
    void truncateKeepsOddIndexedBytes() {
        byte[] full = Hex.decode("00112233445566778899AABBCCDDEEFF");
        assertArrayEquals(Hex.decode("1133557799BBDDFF"), AesCmac.truncate(full));
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> AesCmac.mac(new byte[15], new byte[0]));
    }

    private static void assertMac(String expectedHex, String messageHex) {
        assertArrayEquals(Hex.decode(expectedHex), AesCmac.mac(KEY, Hex.decode(messageHex)));
    }
}
