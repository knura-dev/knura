package io.knura.core.backup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Writes a secret as short rows of hex that a person can copy onto paper and type back in. Each
 * row holds up to 8 bytes as four groups of four digits, then a two-digit check, so a typo is
 * caught on the row it is in instead of as a wrong key later:
 *
 * <pre>
 *   1  3F9A 21C0 7B44 E2D1  5C
 *   2  0A6E 91F3 22B8 4C07  E9
 * </pre>
 *
 * <p>The check is the first byte of SHA-256 over {@code "Knura sheet" || label || row number ||
 * row bytes}, so a row copied into the wrong place or under the wrong heading fails too. While
 * reading, case, spaces and dashes don't matter, and O, I and L count as 0, 1 and 1.
 */
public final class RecoverySheet {
    /** Bytes per row. */
    public static final int ROW_BYTES = 8;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** A row that doesn't read back, with its 1-based number. */
    public static final class SheetException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int row;

        SheetException(int row, String message) {
            super(message);
            this.row = row;
        }

        /** The row with the problem, from 1, or 0 when it's about the rows as a whole. */
        public int row() {
            return row;
        }
    }

    private RecoverySheet() {}

    /** How many rows a secret of {@code length} bytes takes. */
    public static int rowCount(int length) {
        return (length + ROW_BYTES - 1) / ROW_BYTES;
    }

    /** The rows for {@code secret} under {@code label} (such as "master.key"), without row numbers. */
    public static List<String> rows(String label, byte[] secret) {
        List<String> rows = new ArrayList<>();
        for (int r = 0; r < rowCount(secret.length); r++) {
            int from = r * ROW_BYTES;
            byte[] part = java.util.Arrays.copyOfRange(secret, from, Math.min(secret.length, from + ROW_BYTES));
            String hex = HEX.formatHex(part);
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 4) {
                row.append(hex, i, Math.min(hex.length(), i + 4)).append(' ');
            }
            row.append(' ').append(HEX.toHexDigits(check(label, r + 1, part)));
            rows.add(row.toString());
        }
        return rows;
    }

    /** Reads row number {@code number} (from 1) as typed, checking it. Returns its bytes. */
    public static byte[] readRow(String label, int number, String typed) throws SheetException {
        String digits = normalise(typed);
        if (digits.length() < 4 || digits.length() % 2 != 0) {
            throw new SheetException(number, "row " + number + " should be 4 groups of 4 characters and a 2-character check");
        }
        byte[] all;
        try {
            all = HEX.parseHex(digits);
        } catch (IllegalArgumentException e) {
            throw new SheetException(number, "row " + number + " has a character that isn't 0-9 or A-F");
        }
        byte[] part = java.util.Arrays.copyOf(all, all.length - 1);
        if (part.length > ROW_BYTES || check(label, number, part) != all[all.length - 1]) {
            throw new SheetException(number, "row " + number + " doesn't match its check: look for a typo in that row");
        }
        return part;
    }

    /** Reads every row of a secret of {@code length} bytes. */
    public static byte[] read(String label, List<String> typed, int length) throws SheetException {
        if (typed.size() != rowCount(length)) {
            throw new SheetException(0, label + " has " + rowCount(length) + " rows, not " + typed.size());
        }
        byte[] secret = new byte[length];
        int at = 0;
        for (int r = 0; r < typed.size(); r++) {
            byte[] part = readRow(label, r + 1, typed.get(r));
            int expected = Math.min(ROW_BYTES, length - at);
            if (part.length != expected) {
                throw new SheetException(r + 1, "row " + (r + 1) + " should have " + expected * 2 + " characters before its check");
            }
            System.arraycopy(part, 0, secret, at, part.length);
            at += part.length;
        }
        return secret;
    }

    private static String normalise(String typed) {
        StringBuilder s = new StringBuilder();
        for (char c : typed.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case ' ', '-', '\t' -> { }
                case 'O' -> s.append('0');
                case 'I', 'L' -> s.append('1');
                default -> s.append(c);
            }
        }
        return s.toString();
    }

    private static byte check(String label, int number, byte[] part) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("Knura sheet".getBytes(StandardCharsets.US_ASCII));
            sha.update(label.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) number);
            sha.update(part);
            return sha.digest()[0];
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
