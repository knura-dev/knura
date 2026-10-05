package io.knura.core.crypto;

import java.util.HexFormat;

/** Uppercase hex encoding, matching how NTAG 424 DNA mirrors bytes into URLs. */
public final class Hex {
    private static final HexFormat FORMAT = HexFormat.of().withUpperCase();

    private Hex() {}

    /** Decodes hex in either case. Throws {@link IllegalArgumentException} on odd length or bad digits. */
    public static byte[] decode(String hex) {
        return FORMAT.parseHex(hex);
    }

    public static String encode(byte[] bytes) {
        return FORMAT.formatHex(bytes);
    }
}
