package io.knura.knurd;

import io.knura.core.crypto.Hex;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * knurd settings, read from a properties file. See {@code knurd.example.properties} for every
 * key. Keys are 32 hex digits; tag UIDs in key names are matched case-insensitively.
 */
public record KnurdConfig(String host, int port, TagKeys keys, Path countersFile) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8424;

    private static final String FILE_KEY_PREFIX = "sdm.fileReadKey.";

    public static KnurdConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        Path dir = file.toAbsolutePath().getParent();
        return fromProperties(p, dir);
    }

    /** Builds a config; relative paths in {@code p} resolve against {@code baseDir}. */
    static KnurdConfig fromProperties(Properties p, Path baseDir) {
        String host = p.getProperty("listen.host", DEFAULT_HOST).trim();
        int port;
        try {
            port = Integer.parseInt(p.getProperty("listen.port", String.valueOf(DEFAULT_PORT)).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("listen.port must be a number", e);
        }

        Map<String, byte[]> perTag = new HashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith(FILE_KEY_PREFIX)) {
                String uid = name.substring(FILE_KEY_PREFIX.length()).toUpperCase(Locale.ROOT);
                perTag.put(uid, key(p, name));
            }
        }
        byte[] meta = optionalKey(p, "sdm.metaReadKey");
        byte[] shared = optionalKey(p, "sdm.fileReadKey");
        if (shared == null && perTag.isEmpty()) {
            throw new IllegalArgumentException("set sdm.fileReadKey or at least one sdm.fileReadKey.<UID>");
        }

        Path counters = baseDir.resolve(p.getProperty("counters.file", "knurd-counters.properties").trim());
        return new KnurdConfig(host, port, new TagKeys(meta, shared, perTag), counters);
    }

    private static byte[] optionalKey(Properties p, String name) {
        String value = p.getProperty(name);
        return value == null || value.isBlank() ? null : key(p, name);
    }

    private static byte[] key(Properties p, String name) {
        try {
            byte[] key = Hex.decode(p.getProperty(name).trim());
            if (key.length != 16) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be 32 hex digits (an AES-128 key)", e);
        }
    }
}
