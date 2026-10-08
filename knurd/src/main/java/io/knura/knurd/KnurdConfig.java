package io.knura.knurd;

import io.knura.core.crypto.Hex;
import io.knura.core.resolver.OwnerIds;
import io.knura.core.resolver.OwnerKey;
import io.knura.core.resolver.ResolverClient;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
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
 * {@code actionsFile} is null when no knurgN actions are set up. {@code publicUrl} is the HTTPS
 * address phones use to reach knurd, without a trailing slash, or null when it is not set.
 * {@code resolver} is null unless this knurd is registered with a tap resolver such as
 * tap.knura.io ({@code knurctl resolver register}).
 */
public record KnurdConfig(
        String host,
        int port,
        TagKeys keys,
        Path countersFile,
        Path actionsFile,
        String publicUrl,
        Resolver resolver) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8424;

    private static final String FILE_KEY_PREFIX = "sdm.fileReadKey.";

    /**
     * The tap resolver this knurd is registered with: its URL, this knurd's owner ID there, and
     * the owner's public key, which every record the resolver serves must be signed with.
     */
    public record Resolver(String url, String owner, String publicKey) {}

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
        byte[] master = optionalKey(p, "master.key");
        byte[] meta = optionalKey(p, "sdm.metaReadKey");
        byte[] shared = optionalKey(p, "sdm.fileReadKey");
        TagKeys keys;
        if (master != null) {
            if (meta != null || shared != null) {
                throw new IllegalArgumentException(
                        "master.key derives the SDM keys; remove sdm.metaReadKey and sdm.fileReadKey");
            }
            keys = TagKeys.derived(master, perTag);
        } else if (shared == null && perTag.isEmpty()) {
            throw new IllegalArgumentException("set master.key, sdm.fileReadKey or at least one sdm.fileReadKey.<UID>");
        } else {
            keys = new TagKeys(meta, shared, perTag);
        }

        Path counters = baseDir.resolve(p.getProperty("counters.file", "knurd-counters.properties").trim());
        String actions = p.getProperty("actions.file", "").trim();
        String publicUrl = publicUrl(p.getProperty("public.url", "").trim());
        return new KnurdConfig(
                host,
                port,
                keys,
                counters,
                actions.isEmpty() ? null : baseDir.resolve(actions),
                publicUrl,
                resolver(p, publicUrl));
    }

    /** The resolver.* settings knurctl resolver register writes, or null if there are none. */
    static Resolver resolver(Properties p, String publicUrl) {
        String owner = p.getProperty("resolver.owner", "").trim();
        if (owner.isEmpty()) {
            return null;
        }
        if (!OwnerIds.isValid(owner)) {
            throw new IllegalArgumentException("resolver.owner must be 10 characters from a-z and 2-9: " + owner);
        }
        if (publicUrl == null) {
            throw new IllegalArgumentException("resolver.owner needs public.url: the address the resolver sends taps to");
        }
        String url = p.getProperty("resolver.url", ResolverClient.DEFAULT_URL).trim();
        try {
            url = new ResolverClient(url).baseUrl();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("resolver.url: " + e.getMessage(), e);
        }
        String publicKey = p.getProperty("resolver.publicKey", "").trim();
        if (!publicKey.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("resolver.publicKey must be 64 hex digits");
        }
        String seed = p.getProperty("resolver.key", "").trim();
        if (!seed.isEmpty()) {
            // knurd never signs; this only catches a resolver.key pasted from another owner.
            OwnerKey.load(seed, publicKey);
        }
        return new Resolver(url, owner, publicKey.toLowerCase(Locale.ROOT));
    }

    /**
     * Checks {@code public.url}: phones refuse to trust a plain http tap page, and a tap link
     * sent in clear can be read on the way, so it must be https unless it is this computer.
     */
    static String publicUrl(String value) {
        if (value.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("public.url is not a valid URL: " + value, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException(
                    "public.url must look like https://your-computer.your-tailnet.ts.net: " + value);
        }
        if (scheme.equals("http") && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("public.url must start with https:// (got " + value + ")");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
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
