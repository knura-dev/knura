package io.knura.core.ntag424;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.knura.core.crypto.Hex;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TapUrlLayoutTest {
    private static final TapUrlLayout LOCAL = TapUrlLayout.forBaseUrl("http://127.0.0.1:8424/tap");

    @Test
    void templateHasPlaceholdersForPiccDataAndMac() {
        assertEquals(
                "http://127.0.0.1:8424/tap?e=00000000000000000000000000000000&c=0000000000000000",
                LOCAL.template());
    }

    @Test
    void offsetsPointAtThePlaceholders() {
        byte[] file = LOCAL.ndefFile();
        String text = new String(file, StandardCharsets.US_ASCII);
        assertEquals(text.indexOf("e=") + 2, LOCAL.piccDataOffset());
        assertEquals(text.indexOf("c=") + 2, LOCAL.macOffset());
        assertEquals(file.length - 16, LOCAL.macOffset(), "the MAC ends the URL");
    }

    @Test
    void ndefFileIsOneShortUriRecord() {
        byte[] file = LOCAL.ndefFile();
        int length = (file[0] & 0xFF) << 8 | (file[1] & 0xFF);
        assertEquals(file.length - 2, length);
        // Header D1 (short well-known record), type length 1, payload length, type 'U', no prefix.
        assertEquals(
                String.format("D101%02X5500", 1 + LOCAL.template().length()),
                Hex.encode(Arrays.copyOfRange(file, 2, 7)));
        assertEquals(LOCAL.template(), TapUrlLayout.decodeUri(Arrays.copyOfRange(file, 2, file.length)));
    }

    @Test
    void fileSettingsFollowAn12196Layout() {
        // FileOption 40 (SDM, plain), AccessRights 00E0 (read free, write and change key 0),
        // SDMOptions C1, SDMAccessRights FF12 (meta key 1, file key 2), then the PICC data, MAC
        // input and MAC offsets, little endian. Offsets here: e= value at 35, c= value at 70.
        assertEquals("4000E0C1FF12" + "230000" + "460000" + "460000", Hex.encode(LOCAL.fileSettings(1, 2)));
    }

    @Test
    void addsToAnExistingQuery() {
        TapUrlLayout layout = TapUrlLayout.forBaseUrl("https://example.com/t?site=3");
        assertEquals(
                "https://example.com/t?site=3&e=00000000000000000000000000000000&c=0000000000000000",
                layout.template());
    }

    @Test
    void decodesAbbreviatedPrefixes() {
        byte[] message = Hex.decode("D1010B5504" + Hex.encode("knura.io/t".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("https://knura.io/t", TapUrlLayout.decodeUri(message));
    }

    @Test
    void rejectsUnusableBaseUrls() {
        assertThrows(IllegalArgumentException.class, () -> TapUrlLayout.forBaseUrl("ftp://example.com"));
        assertThrows(IllegalArgumentException.class, () -> TapUrlLayout.forBaseUrl("example.com/tap"));
        assertThrows(IllegalArgumentException.class, () -> TapUrlLayout.forBaseUrl("https://example.com/#x"));
        assertThrows(IllegalArgumentException.class,
                () -> TapUrlLayout.forBaseUrl("https://example.com/" + "a".repeat(80)));
    }
}
