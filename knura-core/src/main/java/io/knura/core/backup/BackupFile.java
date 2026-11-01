package io.knura.core.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A passphrase-locked copy of a settings folder: a zip of its files, encrypted with AES-256-GCM
 * under a key stretched from the passphrase with PBKDF2-HMAC-SHA256. JDK only, so it needs no gpg.
 *
 * <p>Layout: {@code "KNURABK1"}, iterations (4 bytes, big-endian), salt (16), nonce (12), then the
 * ciphertext with its 16-byte tag. Everything before the ciphertext is authenticated too, so a
 * changed iteration count or salt fails like a wrong passphrase.
 */
public final class BackupFile {
    /** The file name ending knurctl gives backups. */
    public static final String SUFFIX = ".knurabackup";
    /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256: about half a second on a laptop. */
    public static final int ITERATIONS = 600_000;
    /** Shortest passphrase knurctl accepts. */
    public static final int MIN_PASSPHRASE = 10;

    static final String MANIFEST = "META-INF/knura-backup.properties";
    private static final byte[] MAGIC = "KNURABK1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT = 16;
    private static final int NONCE = 12;
    private static final int HEADER = MAGIC.length + 4 + SALT + NONCE;
    /** Refuse silly counts from a damaged or hostile file, before spending minutes on them. */
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final long MAX_UNPACKED = 64L * 1024 * 1024;

    /** What a backup holds: when it was made and its files, by path relative to the folder. */
    public record Contents(Instant made, Map<String, byte[]> files) {
        public Contents {
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
    }

    /** Thrown when a file isn't a Knura backup, is damaged, or the passphrase is wrong. */
    public static final class BackupException extends Exception {
        private static final long serialVersionUID = 1L;

        BackupException(String message) {
            super(message);
        }
    }

    private BackupFile() {}

    /** Packs and encrypts {@code files} (relative path to content). */
    public static byte[] seal(Map<String, byte[]> files, Instant made, char[] passphrase, SecureRandom random) {
        return seal(files, made, passphrase, random, ITERATIONS);
    }

    static byte[] seal(Map<String, byte[]> files, Instant made, char[] passphrase, SecureRandom random, int iterations) {
        byte[] salt = new byte[SALT];
        byte[] nonce = new byte[NONCE];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        ByteBuffer header = ByteBuffer.allocate(HEADER);
        header.put(MAGIC).putInt(iterations).put(salt).put(nonce);
        byte[] zip = zip(files, made);
        byte[] key = stretch(passphrase, salt, iterations);
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            gcm.updateAAD(header.array());
            byte[] sealed = gcm.doFinal(zip);
            return ByteBuffer.allocate(HEADER + sealed.length).put(header.array()).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no AES-GCM", e);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(zip, (byte) 0);
        }
    }

    /** Decrypts and unpacks a backup made by {@link #seal}. */
    public static Contents open(byte[] data, char[] passphrase) throws BackupException {
        if (data.length < HEADER + 16 || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) {
            throw new BackupException("not a Knura backup file");
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        in.position(MAGIC.length);
        int iterations = in.getInt();
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new BackupException("damaged backup file");
        }
        byte[] salt = new byte[SALT];
        byte[] nonce = new byte[NONCE];
        in.get(salt).get(nonce);
        byte[] key = stretch(passphrase, salt, iterations);
        byte[] zip;
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            gcm.updateAAD(data, 0, HEADER);
            zip = gcm.doFinal(data, HEADER, data.length - HEADER);
        } catch (AEADBadTagException e) {
            throw new BackupException("wrong passphrase, or the backup file is damaged");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no AES-GCM", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        try {
            return unzip(zip);
        } finally {
            Arrays.fill(zip, (byte) 0);
        }
    }

    /**
     * True if {@code path} is a safe relative path to restore into a folder: forward slashes, no
     * {@code ..}, no absolute path or drive letter.
     */
    public static boolean safePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            return false;
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static byte[] stretch(char[] passphrase, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no PBKDF2WithHmacSHA256", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] zip(Map<String, byte[]> files, Instant made) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            Properties manifest = new Properties();
            manifest.setProperty("format", "1");
            manifest.setProperty("made", made.toString());
            zip.putNextEntry(new ZipEntry(MANIFEST));
            manifest.store(zip, "Knura backup");
            zip.closeEntry();
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                if (!safePath(f.getKey()) || f.getKey().equals(MANIFEST)) {
                    throw new IllegalArgumentException("can't back up " + f.getKey());
                }
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static Contents unzip(byte[] data) throws BackupException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Instant made = null;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory()) {
                    continue;
                }
                byte[] content = zip.readNBytes((int) Math.min(Integer.MAX_VALUE, MAX_UNPACKED - total + 1));
                total += content.length;
                if (total > MAX_UNPACKED) {
                    throw new BackupException("backup is too large to restore");
                }
                if (e.getName().equals(MANIFEST)) {
                    Properties p = new Properties();
                    p.load(new ByteArrayInputStream(content));
                    made = Instant.parse(p.getProperty("made"));
                } else if (!safePath(e.getName())) {
                    throw new BackupException("backup holds an unsafe path: " + e.getName());
                } else {
                    files.put(e.getName(), content);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new BackupException("damaged backup file");
        }
        if (made == null) {
            throw new BackupException("damaged backup file");
        }
        return new Contents(made, files);
    }
}
