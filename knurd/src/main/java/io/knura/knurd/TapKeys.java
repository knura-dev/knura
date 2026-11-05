package io.knura.knurd;

import io.knura.core.admin.PrivateFiles;
import io.knura.core.tap.TapSignature;
import java.io.IOException;
import java.io.Reader;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The paired phones' public tap keys (see {@link TapSignature}), in {@link #FILE} next to the
 * admin tokens file. A phone sends its keys once, over the admin API with its token, and from then
 * on can sign taps sent to knurd's public tap address.
 *
 * <p>Each phone's keys are saved together with the hash of the token that sent them, and only
 * count while that phone still has that same token. So removing a phone (from the dashboard, the
 * app or {@code knurctl admin token remove}) also stops its signed taps, and a new phone paired
 * under an old name can't use the old phone's keys.
 */
final class TapKeys {
    private static final System.Logger LOG = System.getLogger("knurd.admin");
    static final String FILE = "knurd-tap-keys.properties";

    private final Path file;
    private final Function<String, Optional<String>> tokenHash;

    /** @param tokenHash a phone name's current token hash, from {@link TokenStore#hashOf} */
    TapKeys(Path file, Function<String, Optional<String>> tokenHash) {
        this.file = file;
        this.tokenHash = tokenHash;
    }

    /** Next to the admin tokens file. */
    static TapKeys beside(Path tokensFile, TokenStore tokens) {
        return new TapKeys(tokensFile.resolveSibling(FILE), tokens::hashOf);
    }

    /**
     * Saves {@code phone}'s keys, replacing any it had. {@code confirm} may be null for a phone
     * that can't make one.
     *
     * @throws IllegalStateException if the phone has no token any more
     */
    synchronized void save(String phone, PublicKey everyday, PublicKey confirm) throws IOException {
        String hash = tokenHash.apply(phone).orElseThrow(() -> new IllegalStateException("no token named " + phone));
        Properties p = read();
        p.remove(phone + ".confirm");
        p.setProperty(phone + ".token", hash);
        p.setProperty(phone + ".everyday", encode(everyday));
        if (confirm != null) {
            p.setProperty(phone + ".confirm", encode(confirm));
        }
        write(p);
    }

    /** {@code phone}'s {@code kind} key, if it has one and still has the token that sent it. */
    synchronized Optional<PublicKey> find(String phone, TapSignature.Kind kind) {
        Optional<String> hash = tokenHash.apply(phone);
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        Properties p;
        try {
            p = read();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "can't read " + file + ", so signed taps aren't accepted: " + e.getMessage());
            return Optional.empty();
        }
        if (!hash.get().equals(p.getProperty(phone + ".token"))) {
            return Optional.empty();
        }
        String key = p.getProperty(phone + "." + kind.jsonName());
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TapSignature.parsePublicKey(key));
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "the " + kind.jsonName() + " tap key of " + phone + " in " + file + " is broken");
            return Optional.empty();
        }
    }

    /** Whether {@code phone} has sent its keys with its current token. */
    synchronized boolean has(String phone) {
        return find(phone, TapSignature.Kind.EVERYDAY).isPresent();
    }

    private Properties read() throws IOException {
        Properties p = new Properties();
        if (!Files.exists(file)) {
            return p;
        }
        String unsafe = PrivateFiles.problem(file);
        if (unsafe != null) {
            throw new IOException(unsafe);
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    private void write(Properties p) throws IOException {
        StringBuilder out = new StringBuilder("# Paired phones' public tap keys, sent by the Knura app. Not secret,"
                + " but\n# a key here lets that phone's signed taps through, so keep it owner-only.\n");
        // Names are [a-z0-9_-] and values base64 or sha256:hex, so plain key=value lines load back as they are.
        for (var e : new TreeMap<>(p).entrySet()) {
            out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        PrivateFiles.replace(file, out.toString());
    }

    private static String encode(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
