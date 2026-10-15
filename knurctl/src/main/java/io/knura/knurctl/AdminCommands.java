package io.knura.knurctl;

import io.knura.core.admin.AdminKeystore;
import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.LocalAddresses;
import io.knura.core.admin.PrivateFiles;
import io.knura.knurctl.Main.UsageException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * {@code knurctl admin ...}: setting up knurd's admin API on knurd's computer (tokens, TLS) and
 * using it from anywhere on the local network with {@code --command-config}.
 */
final class AdminCommands {
    static final String DEFAULT_TOKENS_FILE = "knurd-admin-tokens.properties";
    static final String DEFAULT_KEYSTORE = "knurd-admin.p12";

    private final PrintStream out;
    private final Map<String, String> options;
    private final Path configPath;

    AdminCommands(PrintStream out, Map<String, String> options, Path configPath) {
        this.out = out;
        this.options = options;
        this.configPath = configPath;
    }

    void run(List<String> words) throws IOException, UsageException {
        String sub = words.size() > 1 ? words.get(1) : "";
        if (sub.equals("token") || sub.equals("tls")) {
            String action = words.size() > 2 ? words.get(2) : "";
            String name = words.size() > 3 ? words.get(3) : null;
            int expected = action.equals("list") || action.equals("create") ? 3 : 4;
            if (words.size() != expected) {
                throw new UsageException(sub.equals("tls")
                        ? "use: knurctl admin tls create"
                        : "use: knurctl admin token add <name>, token list, or token remove <name>");
            }
            switch (sub + " " + action) {
                case "token add" -> tokenAdd(name);
                case "token list" -> tokenList();
                case "token remove" -> tokenRemove(name);
                case "tls create" -> tlsCreate();
                default -> throw new UsageException("unknown command: admin " + sub + " " + action);
            }
            return;
        }
        if (words.size() != 2) {
            throw new UsageException("use: knurctl admin whoami|status|tags|taps|token|tls ...");
        }
        switch (sub) {
            case "whoami" -> out.println("Logged in to " + config().url() + " as token \"" + client().get("/v1/whoami").get("token") + "\".");
            case "status" -> status();
            case "tags" -> tags();
            case "taps" -> taps();
            default -> throw new UsageException("unknown command: admin " + sub);
        }
    }

    // ---- Using the API (anywhere, with --command-config) ----

    private CommandConfig config() throws IOException, UsageException {
        return CommandConfig.load(Path.of(options.getOrDefault("--command-config", CommandConfig.DEFAULT_FILE)));
    }

    private AdminClient client() throws IOException, UsageException {
        return new AdminClient(config());
    }

    private void status() throws IOException, UsageException {
        Map<String, Object> s = client().get("/v1/status");
        long up = s.get("uptimeSeconds") instanceof Long l ? l : 0;
        out.printf("knurd is up (for %dd %dh %dm)%n", up / 86400, up / 3600 % 24, up / 60 % 60);
        out.println("Keys:        " + ("master".equals(s.get("keys")) ? "one master key, per-tag keys derived" : "fixed SDM keys"));
        out.println("Public URL:  " + (s.get("publicUrl") == null ? "not set (phones can't reach knurd)" : s.get("publicUrl")));
        out.println("Resolver:    " + (s.get("resolverOwner") == null ? "not registered" : "owner " + s.get("resolverOwner")));
        out.println("Knots:       " + s.get("knots") + " with actions");
        out.println("Tags seen:   " + s.get("tags"));
    }

    @SuppressWarnings("unchecked")
    private void tags() throws IOException, UsageException {
        List<Object> tags = (List<Object>) client().get("/v1/tags").getOrDefault("tags", List.of());
        if (tags.isEmpty()) {
            out.println("knurd hasn't accepted a tap from any tag yet.");
            return;
        }
        out.printf("%-16s %s%n", "UID", "LAST COUNTER");
        for (Object o : tags) {
            Map<String, Object> t = (Map<String, Object>) o;
            out.printf("%-16s %s%n", t.get("uid"), t.get("lastCounter"));
        }
    }

    @SuppressWarnings("unchecked")
    private void taps() throws IOException, UsageException {
        String limit = options.getOrDefault("--limit", "20");
        if (!limit.matches("\\d{1,3}")) {
            throw new UsageException("--limit must be a number from 1 to 200");
        }
        List<Object> taps = (List<Object>) client().get("/v1/taps?limit=" + limit).getOrDefault("taps", List.of());
        if (taps.isEmpty()) {
            out.println("No taps since knurd started.");
            return;
        }
        out.printf("%-24s %-9s %-16s %7s %7s%n", "TIME (UTC)", "RESULT", "UID", "COUNTER", "ACTIONS");
        for (Object o : taps) {
            Map<String, Object> t = (Map<String, Object>) o;
            out.printf("%-24s %-9s %-16s %7s %7s%n", t.get("time"), t.get("status"),
                    t.get("uid") == null ? "-" : t.get("uid"), t.get("counter") == null ? "-" : t.get("counter"),
                    t.get("actions"));
        }
    }

    // ---- Setting up (on knurd's computer, with --config) ----

    /**
     * Makes a token, adds its hash to knurd's tokens file (turning the admin API on in the
     * settings file if needed) and writes the token into a new command config.
     */
    private void tokenAdd(String name) throws IOException, UsageException {
        if (!AdminTokens.isValidName(name)) {
            throw new UsageException("token names are 1-32 of a-z, 0-9, - and _, like laptop or loomstudio");
        }
        Properties config = knurdConfig();
        boolean enabling = config.getProperty("admin.tokens.file", "").isBlank();
        Path tokensFile = resolve(enabling ? DEFAULT_TOKENS_FILE : config.getProperty("admin.tokens.file").trim());
        Map<String, String> tokens = readTokens(tokensFile);
        if (tokens.containsKey(name)) {
            throw new UsageException("there is already a token named " + name + "; remove it first or pick another name");
        }
        URI url = adminUrl(config);
        String fingerprint = null;
        if (!config.getProperty("admin.tls.keystore", "").isBlank()) {
            fingerprint = AdminKeystore.load(
                            resolve(config.getProperty("admin.tls.keystore").trim()),
                            resolve(config.getProperty("admin.tls.password.file", "").trim()))
                    .fingerprint();
        }
        Path outFile = Path.of(options.getOrDefault("--out", CommandConfig.DEFAULT_FILE));
        if (Files.exists(outFile)) {
            throw new UsageException(outFile.toAbsolutePath() + " already exists; pass --out to write somewhere else");
        }

        String token = AdminTokens.generate(new SecureRandom());
        PrivateFiles.create(outFile, CommandConfig.format(url, token, fingerprint, name));
        tokens.put(name, AdminTokens.hash(token));
        PrivateFiles.replace(tokensFile, AdminTokens.format(tokens));
        if (enabling) {
            appendSettings("""
                    # knurd admin API (knurctl admin token add). Its own port, never the tap port: keep it off
                    # the internet. 127.0.0.1 means this computer only; see knurd.example.properties for LAN use.
                    admin.tokens.file=%s
                    """.formatted(DEFAULT_TOKENS_FILE));
        }
        out.println("Made token \"" + name + "\" and wrote it to " + outFile.toAbsolutePath() + " (chmod 600).");
        out.println("knurd keeps only its hash, in " + tokensFile.toAbsolutePath() + ".");
        if (enabling) {
            out.println();
            out.println("Turned the admin API on in " + configPath.toAbsolutePath() + ". Restart knurd, then try:");
        } else {
            out.println("knurd accepts it straight away. Try:");
        }
        out.println("  knurctl admin status --command-config " + outFile);
        out.println("Treat that file like a password. To copy it to another computer use scp, then chmod 600 it there.");
    }

    private void tokenList() throws IOException, UsageException {
        Properties config = knurdConfig();
        String file = config.getProperty("admin.tokens.file", "").trim();
        if (file.isEmpty()) {
            out.println("The admin API is off: no admin.tokens.file in " + configPath.toAbsolutePath() + ".");
            out.println("Turn it on and make a token with: knurctl admin token add <name>");
            return;
        }
        Map<String, String> tokens = readTokens(resolve(file));
        if (tokens.isEmpty()) {
            out.println("No tokens yet. Make one with: knurctl admin token add <name>");
        }
        tokens.keySet().forEach(out::println);
    }

    private void tokenRemove(String name) throws IOException, UsageException {
        Properties config = knurdConfig();
        String file = config.getProperty("admin.tokens.file", "").trim();
        if (file.isEmpty()) {
            throw new UsageException("the admin API is off: no admin.tokens.file in " + configPath.toAbsolutePath());
        }
        Path tokensFile = resolve(file);
        Map<String, String> tokens = readTokens(tokensFile);
        if (tokens.remove(name) == null) {
            throw new UsageException("no token named " + name + " (see knurctl admin token list)");
        }
        PrivateFiles.replace(tokensFile, AdminTokens.format(tokens));
        out.println("Removed token \"" + name + "\". knurd refuses it from now on; no restart needed.");
    }

    /**
     * Makes a self-signed TLS key and certificate for the admin API with the JDK's keytool, plus
     * a random password file, both chmod 600, and adds them to knurd's settings file.
     */
    private void tlsCreate() throws IOException, UsageException {
        Properties config = knurdConfig();
        if (!config.getProperty("admin.tls.keystore", "").isBlank()) {
            throw new UsageException("admin.tls.keystore is already set in " + configPath
                    + "; knurctl won't replace a certificate that command configs are pinned to");
        }
        Path keystore = resolve(DEFAULT_KEYSTORE);
        Path passwordFile = resolve(DEFAULT_KEYSTORE + ".password");
        for (Path p : new Path[] {keystore, passwordFile}) {
            if (Files.exists(p)) {
                throw new UsageException(p + " already exists; move it away first");
            }
        }
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        PrivateFiles.create(passwordFile, Base64.getUrlEncoder().withoutPadding().encodeToString(secret) + "\n");

        // keytool writes with the default umask, so build the keystore in a private folder first.
        Path work = Files.createTempDirectory(keystore.toAbsolutePath().getParent(), ".knurd-tls",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            Path tmp = work.resolve(DEFAULT_KEYSTORE);
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "knurd-admin",
                    "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3650",
                    "-dname", "CN=knurd admin API", "-storetype", "PKCS12", "-keystore", tmp.toString(),
                    "-storepass:file", passwordFile.toString(), "-keypass:file", passwordFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("keytool failed: " + output.strip());
            }
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            Files.move(tmp, keystore, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(passwordFile);
            throw e;
        } finally {
            try (var leftovers = Files.list(work)) {
                for (Path left : leftovers.toList()) {
                    Files.deleteIfExists(left);
                }
            }
            Files.deleteIfExists(work);
        }
        String fingerprint = AdminKeystore.load(keystore, passwordFile).fingerprint();
        appendSettings("""
                # TLS for the admin API (knurctl admin tls create): a self-signed certificate. knurctl
                # trusts it through knurd.fingerprint in its command config, so no CA is needed.
                admin.tls.keystore=%s
                admin.tls.password.file=%s
                """.formatted(DEFAULT_KEYSTORE, DEFAULT_KEYSTORE + ".password"));
        out.println("Made " + keystore.toAbsolutePath() + " and its password file (both chmod 600).");
        out.println("Certificate fingerprint (SHA-256): " + fingerprint);
        out.println("Added admin.tls.keystore and admin.tls.password.file to " + configPath.toAbsolutePath() + ".");
        out.println("Next, to reach the admin API from other computers on your network, set");
        out.println("admin.listen.host there to this computer's LAN address, restart knurd, and make a token:");
        out.println("  knurctl admin token add laptop --admin-url https://<this computer's LAN address>:8425");
    }

    /** Where the new command config should point: --admin-url, else worked out from admin.listen.*. */
    private URI adminUrl(Properties config) throws UsageException {
        String given = options.get("--admin-url");
        boolean tls = !config.getProperty("admin.tls.keystore", "").isBlank();
        String host = config.getProperty("admin.listen.host", "127.0.0.1").trim();
        String port = config.getProperty("admin.listen.port", "8425").trim();
        String value;
        if (given != null) {
            value = given;
        } else if (host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            throw new UsageException("admin.listen.host is " + host + " (every network); pass --admin-url"
                    + " https://<the address other computers use>:" + port);
        } else {
            String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
            value = (tls ? "https://" : "http://") + h + ":" + port;
        }
        URI url;
        try {
            url = CommandConfig.checkUrl(value);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        if (url.getScheme().equals("https") && !tls) {
            throw new UsageException("knurd's admin API has no TLS yet, so it can't serve " + url
                    + "; run knurctl admin tls create first");
        }
        if (url.getScheme().equals("http") && tls) {
            throw new UsageException("knurd's admin API uses TLS; use an https:// --admin-url");
        }
        if (!LocalAddresses.isLoopbackName(url.getHost()) && !tls) {
            throw new UsageException("other computers need TLS to reach the admin API; run knurctl admin tls create first");
        }
        return url;
    }

    private Properties knurdConfig() throws IOException, UsageException {
        if (!Files.exists(configPath)) {
            throw new UsageException(configPath.toAbsolutePath() + " not found. Run admin token and tls commands"
                    + " on knurd's computer, where its settings file is (or pass --config)");
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    private Map<String, String> readTokens(Path file) throws IOException, UsageException {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        String problem = PrivateFiles.problem(file);
        if (problem != null) {
            throw new UsageException(problem);
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        try {
            return new TreeMap<>(AdminTokens.parse(p));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + " (in " + file + ")");
        }
    }

    private Path resolve(String name) {
        Path dir = configPath.toAbsolutePath().getParent();
        return dir.resolve(name);
    }

    private void appendSettings(String lines) throws IOException {
        String existing = Files.readString(configPath, StandardCharsets.UTF_8);
        String separator = existing.isEmpty() || existing.endsWith("\n") ? "\n" : "\n\n";
        Files.writeString(configPath, separator + lines, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
