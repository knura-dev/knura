package io.knura.knurctl;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import java.util.Map;

/**
 * Draws a QR code in a terminal, two modules per character cell with the upper half block. Colours
 * are set explicitly (black on white), so the code scans on dark and light terminals alike.
 */
final class TerminalQr {
    /** Light modules around the code; scanners want some quiet space. */
    private static final int QUIET = 2;
    private static final String RESET = "\u001b[0m";

    private TerminalQr() {}

    static String render(String text) {
        ByteMatrix m;
        try {
            m = Encoder.encode(text, ErrorCorrectionLevel.M, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8"))
                    .getMatrix();
        } catch (WriterException e) {
            throw new IllegalArgumentException("can't make a QR code: " + e.getMessage(), e);
        }
        int size = m.getWidth() + 2 * QUIET;
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < size; y += 2) {
            for (int x = 0; x < size; x++) {
                boolean top = dark(m, x - QUIET, y - QUIET);
                boolean bottom = dark(m, x - QUIET, y + 1 - QUIET);
                out.append("\u001b[").append(top ? "30" : "97").append(';').append(bottom ? "40" : "107").append("m▀");
            }
            out.append(RESET).append('\n');
        }
        return out.toString();
    }

    private static boolean dark(ByteMatrix m, int x, int y) {
        return x >= 0 && y >= 0 && x < m.getWidth() && y < m.getHeight() && m.get(x, y) == 1;
    }
}
