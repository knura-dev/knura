package io.knura.knurctl;

import io.knura.core.crypto.Hex;
import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.provision.TagKeySet;
import io.knura.knurctl.Main.UsageException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * A tag's current keys in a properties file, {@code key0=} to {@code key4=} with 32 hex digits
 * each, for tags set up by other software. A missing slot means that key is still the factory key.
 */
final class KeyFile {
    private KeyFile() {}

    static TagKeySet load(Path file) throws IOException, UsageException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (NoSuchFileException e) {
            throw new UsageException("--from must be factory, knura or a keys file; no file " + file);
        }
        return parse(p, file.toString());
    }

    static TagKeySet parse(Properties p, String source) throws UsageException {
        byte[][] keys = new byte[TagKeyDerivation.KEY_SLOTS][];
        for (int slot = 0; slot < keys.length; slot++) {
            String hex = p.getProperty("key" + slot);
            if (hex == null || hex.isBlank()) {
                keys[slot] = new byte[16];
                continue;
            }
            try {
                keys[slot] = Hex.decode(hex.trim());
            } catch (IllegalArgumentException e) {
                keys[slot] = new byte[0];
            }
            if (keys[slot].length != 16) {
                throw new UsageException("key" + slot + " in " + source + " must be 32 hex digits");
            }
        }
        return TagKeySet.of(keys);
    }
}
