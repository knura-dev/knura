package io.knura.knurd;

import io.knura.core.admin.PrivateFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * A one-time code that lets the first password be set from another computer, for a Knura with no
 * screen, such as a Raspberry Pi. Without it, the first password can only be set on the Knura
 * computer itself. knurd makes it on first start ({@code knurd --home <folder> --lan}), keeps it
 * in an owner-only file and prints it in its log; it is deleted once the password is set.
 */
final class SetupCode {
    static final String FILE = "knurd-setup-code";
    /** No 0/O, 1/I/L: read off a log or a screen without mistakes. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final Path file;

    SetupCode(Path file) {
        this.file = file;
    }

    /** Makes the code if there isn't one; the code. */
    synchronized String ensure() throws IOException {
        String now = read();
        if (now != null) {
            return now;
        }
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        PrivateFiles.replace(file, code + "\n");
        return code.toString();
    }

    /** Whether there is a code waiting to be used. */
    synchronized boolean waiting() {
        return read() != null;
    }

    /** Whether {@code given} is the code: case, spaces and dashes don't matter. */
    synchronized boolean matches(String given) {
        String code = read();
        if (code == null || given == null) {
            return false;
        }
        return MessageDigest.isEqual(clean(code).getBytes(StandardCharsets.UTF_8),
                clean(given).getBytes(StandardCharsets.UTF_8));
    }

    /** Ends the code, once the first password is set. */
    synchronized void used() throws IOException {
        Files.deleteIfExists(file);
    }

    private String read() {
        try {
            if (!Files.exists(file) || PrivateFiles.problem(file) != null) {
                return null; // a code others could read or change is no code
            }
            String s = Files.readString(file, StandardCharsets.UTF_8).strip();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    private static String clean(String s) {
        return s.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
    }
}
