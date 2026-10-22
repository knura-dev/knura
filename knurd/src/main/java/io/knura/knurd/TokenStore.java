package io.knura.knurd;

import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.PrivateFiles;
import java.io.IOException;
import java.io.Reader;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The admin API tokens knurd accepts, from {@code admin.tokens.file}. The file is read again
 * whenever it changes, so {@code knurctl admin token add} and {@code remove} work without a
 * restart. A phone may also remove tokens through {@link AdminServer}. If the file goes missing, becomes readable by other users or can't be parsed, no token
 * is accepted until it is fixed: a problem here locks the admin API rather than opening it.
 */
final class TokenStore {
    private static final System.Logger LOG = System.getLogger("knurd.admin");

    private final Path file;
    private FileTime loadedTime;
    private long loadedSize = -1;
    private Object loadedKey;
    private Map<String, String> tokens = Map.of();
    private String problem;

    /** Throws {@link IOException} if the file exists but is unsafe or broken, so knurd won't start. */
    TokenStore(Path file) throws IOException {
        this.file = file;
        reloadIfChanged();
        if (problem != null && Files.exists(file)) {
            throw new IOException(problem);
        }
    }

    /** The name of the token, or empty if it isn't one of ours. */
    synchronized Optional<String> nameOf(String token) {
        reloadIfChanged();
        if (!AdminTokens.isWellFormed(token)) {
            return Optional.empty();
        }
        String hash = AdminTokens.hash(token);
        String match = null;
        // Look at every entry so the time taken doesn't depend on which one matched.
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            if (AdminTokens.sameHash(hash, e.getValue()) && match == null) {
                match = e.getKey();
            }
        }
        return Optional.ofNullable(match);
    }

    synchronized int count() {
        reloadIfChanged();
        return tokens.size();
    }

    /** The token names, sorted. */
    synchronized List<String> names() {
        reloadIfChanged();
        return new TreeMap<>(tokens).keySet().stream().toList();
    }

    /**
     * Removes a token from the file, like {@code knurctl admin token remove}. Returns false if
     * there is no such token. Throws if the file can't be read or written, for example when it is
     * mounted read-only.
     */
    synchronized boolean remove(String name) throws IOException {
        reloadIfChanged();
        if (problem != null) {
            throw new IOException(problem);
        }
        if (!tokens.containsKey(name)) {
            return false;
        }
        Map<String, String> rest = new TreeMap<>(tokens);
        rest.remove(name);
        PrivateFiles.replace(file, AdminTokens.format(rest));
        reloadIfChanged();
        return true;
    }

    private void reloadIfChanged() {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            fail(file + " does not exist yet; create a token with: knurctl admin token add <name>");
            return;
        } catch (IOException e) {
            fail("can't read " + file + ": " + e.getMessage());
            return;
        }
        if (problem == null && attrs.lastModifiedTime().equals(loadedTime) && attrs.size() == loadedSize
                && java.util.Objects.equals(attrs.fileKey(), loadedKey)) {
            return;
        }
        try {
            String unsafe = PrivateFiles.problem(file);
            if (unsafe != null) {
                fail(unsafe);
                return;
            }
            Properties p = new Properties();
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            }
            tokens = AdminTokens.parse(p);
            problem = null;
            loadedTime = attrs.lastModifiedTime();
            loadedSize = attrs.size();
            loadedKey = attrs.fileKey();
        } catch (IOException | IllegalArgumentException e) {
            fail("can't use " + file + ": " + e.getMessage());
        }
    }

    private void fail(String why) {
        if (!why.equals(problem)) {
            LOG.log(Level.WARNING, "admin API accepts no tokens: {0}", why);
        }
        problem = why;
        tokens = Map.of();
        loadedTime = null;
        loadedSize = -1;
        loadedKey = null;
    }
}
