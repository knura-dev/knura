package io.knura.knurd;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Keeps counters in a small text file ({@code UID=counter} per line) so replay protection
 * survives restarts. Every accepted tap rewrites the file through a temp file and an atomic
 * rename, which is plenty for a home or small deployment; a database store can replace it later.
 */
public final class FileCounterStore implements CounterStore {
    private final Path file;
    private final Map<String, Integer> counters = new TreeMap<>();

    public FileCounterStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            Properties saved = new Properties();
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                saved.load(in);
            }
            for (String uid : saved.stringPropertyNames()) {
                try {
                    counters.put(uid, Integer.parseInt(saved.getProperty(uid).trim()));
                } catch (NumberFormatException e) {
                    throw new IOException("bad counter for " + uid + " in " + file, e);
                }
            }
        }
    }

    @Override
    public synchronized boolean advance(String uidHex, int counter) {
        Integer last = counters.get(uidHex);
        if (last != null && counter <= last) {
            return false;
        }
        counters.put(uidHex, counter);
        try {
            save();
        } catch (IOException e) {
            // Refuse the tap rather than accept one we could not record.
            if (last == null) {
                counters.remove(uidHex);
            } else {
                counters.put(uidHex, last);
            }
            throw new UncheckedIOException("could not save counters to " + file, e);
        }
        return true;
    }

    @Override
    public synchronized int last(String uidHex) {
        return counters.getOrDefault(uidHex, -1);
    }

    private void save() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                out.write("# knurd tap counters: last accepted SDM read counter per tag UID\n");
                for (Map.Entry<String, Integer> e : counters.entrySet()) {
                    out.write(e.getKey() + "=" + e.getValue() + "\n");
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
