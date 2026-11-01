package io.knura.core.backup;

import io.knura.core.admin.PrivateFiles;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/**
 * Remembers, in {@code knura-backup.properties} next to knurd.properties, when this master key was
 * last backed up, so knurctl, knurd and the app can remind people who haven't. It holds no secret:
 * only a short key ID (see {@link #keyId}) and dates. A record for another key ID is ignored, so a
 * new master key starts out "not backed up".
 */
public final class BackupRecord {
    public static final String FILE_NAME = "knura-backup.properties";

    /** How the key was last backed up. */
    public enum Kind {
        FILE,
        SHEET
    }

    /** When this master key was last backed up to a file and to a sheet, if ever. */
    public record Status(Optional<Instant> file, Optional<Instant> sheet) {
        public boolean any() {
            return file.isPresent() || sheet.isPresent();
        }

        /** The newer of the two, if any. */
        public Optional<Instant> latest() {
            if (file.isEmpty()) {
                return sheet;
            }
            if (sheet.isEmpty()) {
                return file;
            }
            return Optional.of(file.get().isAfter(sheet.get()) ? file.get() : sheet.get());
        }
    }

    private BackupRecord() {}

    /**
     * A short name for a master key that says nothing about the key: the first 4 bytes of
     * SHA-256 over {@code "Knura key id" || key}, as 8 hex digits. Printed on sheets so a sheet
     * can be matched to a setup.
     */
    public static String keyId(byte[] masterKey) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("Knura key id".getBytes(StandardCharsets.US_ASCII));
            sha.update(masterKey);
            return HexFormat.of().withUpperCase().formatHex(sha.digest(), 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the record in {@code folder} says about the key with this ID. Never throws for a bad file. */
    public static Status read(Path folder, String keyId) {
        Properties p = load(folder.resolve(FILE_NAME));
        if (!keyId.equals(p.getProperty("key.id", "").trim())) {
            return new Status(Optional.empty(), Optional.empty());
        }
        return new Status(instant(p, "file.made"), instant(p, "sheet.made"));
    }

    /** Notes that the key with this ID was backed up as {@code kind} at {@code when}. */
    public static void record(Path folder, String keyId, Kind kind, Instant when) throws IOException {
        Path file = folder.resolve(FILE_NAME);
        Properties p = load(file);
        if (!keyId.equals(p.getProperty("key.id", "").trim())) {
            p.clear();
            p.setProperty("key.id", keyId);
        }
        p.setProperty(kind == Kind.FILE ? "file.made" : "sheet.made", when.toString());
        StringWriter text = new StringWriter();
        try {
            p.store(text, " When this master key was last backed up (knurctl backup). No secrets here.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PrivateFiles.replace(file, text.toString());
    }

    private static Properties load(Path file) {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                // An unreadable record counts as no record.
                p.clear();
            }
        }
        return p;
    }

    private static Optional<Instant> instant(Properties p, String name) {
        String value = p.getProperty(name, "").trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
