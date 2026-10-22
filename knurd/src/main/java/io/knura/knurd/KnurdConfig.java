package io.knura.knurd;

import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.LocalAddresses;
import io.knura.core.admin.PrivateFiles;
import io.knura.core.crypto.Hex;
import io.knura.core.resolver.OwnerIds;
import io.knura.core.resolver.OwnerKey;
import io.knura.core.resolver.ResolverClient;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * knurd settings, read from a properties file. See {@code knurd.example.properties} for every
 * key. Keys are 32 hex digits; tag UIDs in key names are matched case-insensitively.
 * {@code actionsFile} is null when no knurgN actions are set up. {@code publicUrl} is the HTTPS
 * address phones use to reach knurd, without a trailing slash, or null when it is not set.
 * {@code resolver} is null unless this knurd is registered with a tap resolver such as
 * tap.knura.io ({@code knurctl resolver register}). {@code admin} is null unless the admin API
 * is turned on with {@code admin.tokens.file} or {@code admin.security=plaintext}.
 */
public record KnurdConfig(
        String host,
        int port,
        TagKeys keys,
        Path countersFile,
        Path actionsFile,
        String publicUrl,
        Resolver resolver,
        Admin admin) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8424;
    public static final int DEFAULT_ADMIN_PORT = 8425;

    private static final String FILE_KEY_PREFIX = "sdm.fileReadKey.";
    /**
     * The tap resolver this knurd is registered with: its URL, this knurd's owner ID there, and
     * the owner's public key, which every record the resolver serves must be signed with.
     */
    public record Resolver(String url, String owner, String publicKey) {}

    /**
     * The admin API's own listener, separate from the tap port so the tap port can be put on the
     * internet and this one never is. {@code keystore} and {@code passwordFile} are null when it
     * serves plain HTTP, which is only allowed on this computer (loopback). With {@code
     * plaintext}, there is no login ({@code tokensFile} is null) and it must be loopback. {@code
     * provisionTokens} names the tokens allowed to set tags up through a phone ({@link NfcRelay});
     * it is empty unless {@code admin.provision.tokens} is set.
     */
    public record Admin(
            String host, int port, boolean plaintext, Path tokensFile, Path keystore, Path passwordFile, Path auditFile,
            Set<String> provisionTokens) {
        public boolean tls() {
            return keystore != null;
        }
    }

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
        byte[] master = masterKey(p, baseDir);
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
        Admin admin = admin(p, baseDir, port);
        if (admin != null && !admin.provisionTokens().isEmpty()) {
            if (!"master".equals(keys.mode())) {
                throw new IllegalArgumentException("admin.provision.tokens needs master.key: knurd works out each"
                        + " tag's keys from it while a phone sets the tag up");
            }
            if (publicUrl == null) {
                throw new IllegalArgumentException("admin.provision.tokens needs public.url: tags set up through a phone"
                        + " must point somewhere phones can reach");
            }
        }
        return new KnurdConfig(
                host,
                port,
                keys,
                counters,
                actions.isEmpty() ? null : baseDir.resolve(actions),
                publicUrl,
                resolver(p, publicUrl),
                admin);
    }

    /**
     * The admin.* settings, or null when neither admin.tokens.file nor admin.security is set. The admin listener must be
     * this computer or a local network address (or every interface, for containers), never a
     * public one, and anything but loopback must use TLS so tokens never cross a network in clear.
     */
    static Admin admin(Properties p, Path baseDir, int tapPort) {
        String tokens = p.getProperty("admin.tokens.file", "").trim();
        String security = p.getProperty("admin.security", "").trim().toLowerCase(Locale.ROOT);
        if (tokens.isEmpty() && security.isEmpty()) {
            for (String name : p.stringPropertyNames()) {
                if (name.startsWith("admin.") && !p.getProperty(name).isBlank()) {
                    throw new IllegalArgumentException(name + " needs admin.tokens.file or admin.security=plaintext,"
                            + " which turn the admin API on");
                }
            }
            return null;
        }
        boolean plaintext = switch (security) {
            case "", "token" -> false;
            case "plaintext" -> true;
            default -> throw new IllegalArgumentException("admin.security must be token or plaintext, not " + security);
        };
        if (!plaintext && tokens.isEmpty()) {
            throw new IllegalArgumentException("admin.security=token needs admin.tokens.file"
                    + " (knurctl admin token add makes it)");
        }
        String host = p.getProperty("admin.listen.host", DEFAULT_HOST).trim();
        int port;
        try {
            port = Integer.parseInt(p.getProperty("admin.listen.port", String.valueOf(DEFAULT_ADMIN_PORT)).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("admin.listen.port must be a number", e);
        }
        if (port == tapPort) {
            throw new IllegalArgumentException("admin.listen.port must differ from listen.port: the tap port"
                    + " may be on the internet and the admin port never should be");
        }
        boolean loopback = adminHost(host);
        Set<String> provisionTokens = provisionTokens(p.getProperty("admin.provision.tokens", ""));
        String audit = p.getProperty("admin.audit.file", "knurd-admin-audit.log").trim();
        if (plaintext) {
            if (!loopback) {
                throw new IllegalArgumentException("admin.security=plaintext has no login, so it only listens on this"
                        + " computer: set admin.listen.host=127.0.0.1, or use admin.security=token for " + host);
            }
            // No login means nothing to protect in transit on loopback; TLS and tokens don't apply.
            if (!provisionTokens.isEmpty()) {
                throw new IllegalArgumentException("admin.provision.tokens needs admin.security=token: plaintext mode"
                        + " only answers this computer, so no phone could reach it");
            }
            return new Admin(host, port, true, null, null, null, baseDir.resolve(audit), Set.of());
        }
        String keystore = p.getProperty("admin.tls.keystore", "").trim();
        String password = p.getProperty("admin.tls.password.file", "").trim();
        if (keystore.isEmpty() != password.isEmpty()) {
            throw new IllegalArgumentException("set both admin.tls.keystore and admin.tls.password.file, or neither");
        }
        if (!loopback && keystore.isEmpty()) {
            throw new IllegalArgumentException("admin.listen.host " + host + " is reachable from other computers, so"
                    + " the admin API needs TLS: set admin.tls.keystore and admin.tls.password.file"
                    + " (knurctl admin tls create makes them)");
        }
        return new Admin(
                host,
                port,
                false,
                baseDir.resolve(tokens),
                keystore.isEmpty() ? null : baseDir.resolve(keystore),
                password.isEmpty() ? null : baseDir.resolve(password),
                baseDir.resolve(audit),
                provisionTokens);
    }

    /** admin.provision.tokens: token names, separated by commas. */
    static Set<String> provisionTokens(String value) {
        Set<String> names = new TreeSet<>();
        for (String part : value.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!AdminTokens.isValidName(name)) {
                throw new IllegalArgumentException("admin.provision.tokens: not a token name: " + name);
            }
            names.add(name);
        }
        return Set.copyOf(names);
    }

    /**
     * Checks admin.listen.host and says whether it is loopback. It must be an IP address (or
     * localhost), so a DNS answer can't move the admin API onto a public address.
     */
    private static boolean adminHost(String host) {
        if (LocalAddresses.isLoopbackName(host)) {
            return true;
        }
        if (!host.matches("[0-9.]+|[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*")) {
            throw new IllegalArgumentException("admin.listen.host must be an IP address such as 127.0.0.1 or"
                    + " 192.168.1.20, not a name: " + host);
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("admin.listen.host is not a valid IP address: " + host, e);
        }
        if (!address.isAnyLocalAddress() && !LocalAddresses.isLocalNetwork(address)) {
            throw new IllegalArgumentException("admin.listen.host " + host + " is a public internet address; the"
                    + " admin API listens only on this computer or your local network");
        }
        return address.isLoopbackAddress();
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

    /**
     * {@code master.key}, or the key alone in the file {@code master.key.file} names, which is how
     * a Kubernetes or Docker secret hands it over. Like knurgN's secrets file, the file must not
     * be readable by other users. Null when neither is set.
     */
    static byte[] masterKey(Properties p, Path baseDir) {
        byte[] inline = optionalKey(p, "master.key");
        String name = p.getProperty("master.key.file", "").trim();
        if (name.isEmpty()) {
            return inline;
        }
        if (inline != null) {
            throw new IllegalArgumentException("set master.key or master.key.file, not both");
        }
        Path file = baseDir.resolve(name);
        String text;
        try {
            String problem = PrivateFiles.problem(file);
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("master.key.file not found: " + file, e);
        } catch (IOException e) {
            throw new IllegalArgumentException("can't read master.key.file " + file + ": " + e.getMessage(), e);
        }
        return parseKey("master.key.file", text);
    }

    private static byte[] optionalKey(Properties p, String name) {
        String value = p.getProperty(name);
        return value == null || value.isBlank() ? null : key(p, name);
    }

    private static byte[] key(Properties p, String name) {
        return parseKey(name, p.getProperty(name));
    }

    private static byte[] parseKey(String name, String value) {
        try {
            byte[] key = Hex.decode(value.trim());
            if (key.length != 16) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be 32 hex digits (an AES-128 key)", e);
        }
    }
}
