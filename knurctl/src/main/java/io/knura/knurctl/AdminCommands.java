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
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code knurctl admin ...}: setting up knurd's admin API on knurd's computer (tokens, TLS) and
 * using it from anywhere on the local network with {@code --creds}.
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
        if (sub.equals("pair-phone")) {
            if (words.size() != 3) {
                throw new UsageException("use: knurctl admin pair-phone <name>, like: knurctl admin pair-phone fold7");
            }
            pairPhone(words.get(2));
            return;
        }
        if (words.size() != 2) {
            throw new UsageException("use: knurctl admin whoami|status|tags|taps|token|tls|pair-phone ...");
        }
        switch (sub) {
            case "whoami" -> whoami();
            case "status" -> status();
            case "tags" -> tags();
            case "taps" -> taps();
            default -> throw new UsageException("unknown command: admin " + sub);
        }
    }

    // ---- Using the API (anywhere, with --creds) ----

    /**
     * How to reach knurd: the --creds file if given; else, when knurd.properties here says
     * admin.security=plaintext, knurd's loopback address with no login; else the default creds file.
     */
    private Creds config() throws IOException, UsageException {
        String given = options.get("--creds");
        if (given != null) {
            return Creds.load(Path.of(given));
        }
        Creds plaintext = plaintextCreds();
        if (plaintext != null) {
            return plaintext;
        }
        Path file = Path.of(Creds.DEFAULT_FILE);
        if (!Files.exists(file)) {
            throw new UsageException("no --creds file, and no " + Creds.DEFAULT_FILE + " here. Make one on knurd's"
                    + " computer with: knurctl admin token add <name>. Or, to use knurctl without logging in on knurd's"
                    + " own computer, set admin.security=plaintext in knurd.properties and restart knurd");
        }
        return Creds.load(file);
    }

    /** Creds for a plaintext-mode knurd on this computer, or null if knurd.properties here isn't one. */
    private Creds plaintextCreds() throws IOException, UsageException {
        if (!Files.exists(configPath)) {
            return null;
        }
        Properties config = knurdConfig();
        if (!"plaintext".equalsIgnoreCase(config.getProperty("admin.security", "").trim())) {
            return null;
        }
        String host = config.getProperty("admin.listen.host", "127.0.0.1").trim();
        String port = config.getProperty("admin.listen.port", "8425").trim();
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        URI url;
        try {
            url = Creds.checkUrl("http://" + h + ":" + port);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + " (admin.listen.host/port in " + configPath + ")");
        }
        if (!LocalAddresses.isLoopbackName(url.getHost())) {
            throw new UsageException("admin.security=plaintext needs admin.listen.host=127.0.0.1 in " + configPath);
        }
        return new Creds(url, null, null);
    }

    private AdminClient client() throws IOException, UsageException {
        return new AdminClient(config());
    }

    private void whoami() throws IOException, UsageException {
        Creds creds = config();
        Map<String, Object> me = new AdminClient(creds).get("/v1/whoami");
        if ("plaintext".equals(me.get("security"))) {
            out.println("Connected to " + creds.url() + " in plaintext mode: no login (this computer only).");
        } else {
            out.println("Logged in to " + creds.url() + " as token \"" + me.get("token") + "\".");
        }
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
     * settings file if needed) and writes the token into a new creds file.
     */
    private void tokenAdd(String name) throws IOException, UsageException {
        if (!AdminTokens.isValidName(name)) {
            throw new UsageException("token names are 1-32 of a-z, 0-9, - and _, like laptop or loomstudio");
        }
        Properties config = knurdConfig();
        if ("plaintext".equalsIgnoreCase(config.getProperty("admin.security", "").trim())) {
            throw new UsageException("admin.security is plaintext in " + configPath + ", so knurd doesn't check tokens."
                    + " Change it to admin.security=token first if you want logins");
        }
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
        Path outFile = Path.of(options.getOrDefault("--out", Creds.DEFAULT_FILE));
        if (Files.exists(outFile)) {
            throw new UsageException(outFile.toAbsolutePath() + " already exists; pass --out to write somewhere else");
        }

        String token = AdminTokens.generate(new SecureRandom());
        PrivateFiles.create(outFile, Creds.format(url, token, fingerprint, name));
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
        out.println("  knurctl admin status --creds " + outFile);
        out.println("Treat that file like a password. To copy it to another computer use scp, then chmod 600 it there.");
    }

    /**
     * Pairs a phone running the Knura app: names a token that may set tags up through the phone
     * (admin.provision.tokens) and shows a QR code for the app to scan, holding knurd's admin
     * address, its certificate fingerprint and a one-time pairing code. The app swaps the code for
     * the token at knurd (see {@link AdminTokens}), so the token only ever goes to the phone and a
     * photo of the QR code is useless once it's used or {@link AdminTokens#PAIR_TIME} has passed.
     */
    private void pairPhone(String name) throws IOException, UsageException {
        if (!AdminTokens.isValidName(name)) {
            throw new UsageException("token names are 1-32 of a-z, 0-9, - and _, like fold7 or pixel");
        }
        Properties config = knurdConfig();
        if ("plaintext".equalsIgnoreCase(config.getProperty("admin.security", "").trim())) {
            throw new UsageException("admin.security is plaintext in " + configPath + ", which only answers this"
                    + " computer. Change it to admin.security=token so a phone can log in");
        }
        if (config.getProperty("master.key", "").isBlank()) {
            throw new UsageException("no master.key in " + configPath + "; a phone sets tags up with Knura keys,"
                    + " which knurd works out from it (knurctl init makes one)");
        }
        if (config.getProperty("public.url", "").isBlank()) {
            throw new UsageException("set public.url in " + configPath + " first: tags set up with a phone point"
                    + " there (or at your tap.knura.io link), so phones can tap them");
        }
        if (config.getProperty("admin.tls.keystore", "").isBlank()) {
            throw new UsageException("the phone reaches knurd over your Wi-Fi, which needs TLS: run knurctl admin tls"
                    + " create, and set admin.listen.host to this computer's LAN address (see: ip -4 addr)");
        }
        URI url = adminUrl(config);
        if (LocalAddresses.isLoopbackName(url.getHost())) {
            throw new UsageException("admin.listen.host is " + url.getHost() + ", which only this computer can reach."
                    + " Set admin.listen.host in " + configPath + " to this computer's LAN address (see: ip -4 addr),"
                    + " or pass --admin-url https://<that address>:8425");
        }
        String fingerprint = AdminKeystore.load(
                        resolve(config.getProperty("admin.tls.keystore").trim()),
                        resolve(config.getProperty("admin.tls.password.file", "").trim()))
                .fingerprint();
        boolean enabling = config.getProperty("admin.tokens.file", "").isBlank();
        Path tokensFile = resolve(enabling ? DEFAULT_TOKENS_FILE : config.getProperty("admin.tokens.file").trim());
        Map<String, String> tokens = readTokens(tokensFile);
        if (tokens.containsKey(name)) {
            throw new UsageException("there is already a token named " + name + "; remove it first"
                    + " (knurctl admin token remove " + name + ") or pick another name");
        }

        String code = AdminTokens.startPairing(tokensFile, name, java.time.Instant.now());
        if (!Files.exists(tokensFile)) {
            // knurd adds the phone's token here when the app pairs.
            PrivateFiles.replace(tokensFile, AdminTokens.format(tokens));
        }
        if (enabling) {
            appendSettings("""
                    # knurd admin API (knurctl admin pair-phone). Its own port, never the tap port: keep it off
                    # the internet.
                    admin.tokens.file=%s
                    """.formatted(DEFAULT_TOKENS_FILE));
        }
        addProvisionToken(config, name);

        out.println("Made a pairing code for \"" + name + "\"; when the app uses it, knurd makes the phone's token and"
                + " keeps only its hash, in " + tokensFile.toAbsolutePath() + ".");
        out.println("Added " + name + " to admin.provision.tokens in " + configPath.toAbsolutePath() + ".");
        out.println();
        out.println("Restart knurd so it reads admin.provision.tokens. Then, within " + AdminTokens.PAIR_TIME.toMinutes()
                + " minutes, tap Pair in the Knura app and scan this code. It works once.");
        out.println();
        out.print(TerminalQr.render(pairingLink(url, fingerprint, code)));
        out.println();
        out.println("The phone must be on the same network as");
        out.println(url.getHost() + ", and the firewall must let port " + url.getPort() + " in. On Fedora:");
        out.println("  sudo firewall-cmd --add-port=" + url.getPort() + "/tcp --permanent && sudo firewall-cmd --reload");
        out.println("If the phone is lost: knurctl admin token remove " + name);
    }

    /** What the app scans: {@code knura://pair?url=...&fp=...&code=knurp_...}. */
    static String pairingLink(URI adminUrl, String fingerprint, String code) {
        return AdminTokens.pairingLink(adminUrl, fingerprint, code);
    }

    /** Adds {@code name} to admin.provision.tokens, editing the line if there is one. */
    private void addProvisionToken(Properties config, String name) throws IOException {
        String current = config.getProperty("admin.provision.tokens");
        if (current == null) {
            appendSettings("""
                    # Admin tokens allowed to set tags up through a phone (knurctl admin pair-phone).
                    admin.provision.tokens=%s
                    """.formatted(name));
            return;
        }
        Set<String> names = new TreeSet<>();
        for (String part : current.split(",")) {
            if (!part.isBlank()) {
                names.add(part.trim());
            }
        }
        names.add(name);
        String line = "admin.provision.tokens=" + String.join(",", names);
        List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("\\s*admin\\.provision\\.tokens\\s*[=:].*")) {
                lines.set(i, line);
                replaced = true;
            }
        }
        if (!replaced) {
            lines.add(line);
        }
        Files.write(configPath, lines, StandardCharsets.UTF_8);
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
                    + "; knurctl won't replace a certificate that creds files are pinned to");
        }
        Path keystore = resolve(DEFAULT_KEYSTORE);
        Path passwordFile = resolve(DEFAULT_KEYSTORE + ".password");
        for (Path p : new Path[] {keystore, passwordFile}) {
            if (Files.exists(p)) {
                throw new UsageException(p + " already exists; move it away first");
            }
        }
        String fingerprint = AdminKeystore.create(keystore, passwordFile).fingerprint();
        appendSettings("""
                # TLS for the admin API (knurctl admin tls create): a self-signed certificate. knurctl
                # trusts it through knurd.fingerprint in its creds file, so no CA is needed.
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

    /** Where the new creds file should point: --admin-url, else worked out from admin.listen.*. */
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
            url = Creds.checkUrl(value);
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
