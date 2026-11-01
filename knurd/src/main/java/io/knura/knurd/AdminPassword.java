package io.knura.knurd;

import io.knura.core.admin.PrivateFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The password for signing in to knurd's setup page in a browser. Only a salted PBKDF2-SHA256
 * hash is kept, in an owner-only file next to {@code knurd.properties}:
 *
 * <pre>pbkdf2-sha256$310000$&lt;salt&gt;$&lt;hash&gt;</pre>
 *
 * <p>If anyone but the owner can read or change the file, every password is refused (it fails
 * closed), as with the tokens file.
 */
final class AdminPassword {
    static final String FILE = "knurd-admin-password";
    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 256;
    static final int ITERATIONS = 310_000;
    private static final String SCHEME = "pbkdf2-sha256";

    private final Path file;
    private final SecureRandom random;
    private final int iterations;

    AdminPassword(Path file) {
        this(file, new SecureRandom(), ITERATIONS);
    }

    /** For tests, which use fewer rounds. */
    AdminPassword(Path file, SecureRandom random, int iterations) {
        this.file = file;
        this.random = random;
        this.iterations = iterations;
    }

    Path file() {
        return file;
    }

    boolean isSet() {
        return Files.exists(file);
    }

    /** Why {@code password} can't be used, or null if it can. */
    static String problem(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "use at least " + MIN_LENGTH + " characters";
        }
        if (password.length() > MAX_LENGTH) {
            return "use at most " + MAX_LENGTH + " characters";
        }
        return null;
    }

    /** Replaces the password. Call {@link #problem} first. */
    synchronized void set(String password) throws IOException {
        if (problem(password) != null) {
            throw new IllegalArgumentException(problem(password));
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        PrivateFiles.replace(file, SCHEME + "$" + iterations + "$" + b64.encodeToString(salt) + "$"
                + b64.encodeToString(hash(password, salt, iterations)) + "\n");
    }

    /** Whether {@code password} is the one set. False when none is set or the file is unsafe. */
    synchronized boolean matches(String password) throws IOException {
        if (password == null || password.length() > MAX_LENGTH || !isSet()) {
            return false;
        }
        if (PrivateFiles.problem(file) != null) {
            System.getLogger("knurd.admin").log(System.Logger.Level.ERROR,
                    file + " can be read or changed by other users, so no password is accepted; run: chmod 600 " + file);
            return false;
        }
        String[] parts = Files.readString(file, StandardCharsets.UTF_8).trim().split("\\$");
        if (parts.length != 4 || !parts[0].equals(SCHEME)) {
            return false;
        }
        try {
            int rounds = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] want = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(want, hash(password, salt, rounds));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] hash(String password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is missing from this Java", e);
        } finally {
            spec.clearPassword();
        }
    }
}
