package io.knura.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** QrCode's codes, read back with ZXing, a widely used scanner library. */
class QrCodeTest {
    private static String read(boolean[][] m) throws Exception {
        int quiet = 4;
        int scale = 3;
        int side = (m.length + 2 * quiet) * scale;
        int[] pixels = new int[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int my = y / scale - quiet;
                int mx = x / scale - quiet;
                boolean dark = my >= 0 && mx >= 0 && my < m.length && mx < m.length && m[my][mx];
                pixels[y * side + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(side, side, pixels)));
        return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE)).getText();
    }

    private static int zxingVersion(String text) throws Exception {
        // ASCII only, without a character set hint, so ZXing adds no ECI header and sizes as QrCode does.
        return Encoder.encode(text, ErrorCorrectionLevel.M, Map.of(EncodeHintType.QR_COMPACT, Boolean.FALSE))
                .getVersion().getVersionNumber();
    }

    @Test
    void pairingLinkReadsBack() throws Exception {
        String link = "knura://pair?url=https%3A%2F%2F192.168.1.50%3A8426&fp="
                + "3f".repeat(32) + "&code=knurp_" + "A".repeat(43);
        boolean[][] m = QrCode.encode(link);
        assertEquals(link, read(m));
        assertEquals(zxingVersion(link) * 4 + 17, m.length, "same size as ZXing picks");
    }

    @Test
    void everyLengthUpToVersion15ReadsBack() throws Exception {
        Random random = new Random(42);
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_%&=:/?.";
        for (int len = 1; len <= 400; len += (len < 60 ? 1 : 7)) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < len; i++) {
                s.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = s.toString();
            boolean[][] m = QrCode.encode(text);
            assertEquals(text, read(m), "length " + len);
            assertEquals(zxingVersion(text) * 4 + 17, m.length, "size for length " + len);
        }
    }

    @Test
    void unicodeAsUtf8() throws Exception {
        assertEquals("Café ☕ knura", read(QrCode.encode("Café ☕ knura")));
    }
}
