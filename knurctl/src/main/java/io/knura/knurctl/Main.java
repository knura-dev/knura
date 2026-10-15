package io.knura.knurctl;

import io.knura.core.crypto.Hex;
import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.ntag424.Ntag424;
import io.knura.core.ntag424.Ntag424Exception;
import io.knura.core.ntag424.TapUrlLayout;
import io.knura.core.provision.TagKeySet;
import io.knura.core.provision.TagProvisioner;
import io.knura.core.resolver.OwnerIds;
import io.knura.core.resolver.OwnerKey;
import io.knura.core.resolver.OwnerRecord;
import io.knura.core.resolver.ResolverCheck;
import io.knura.core.resolver.ResolverClient;
import io.knura.core.sdm.SdmVerificationException;
import io.knura.core.sdm.VerifiedTap;
import io.knura.knurctl.PcscReader.ReaderException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;

/** knurctl: sets up NTAG 424 DNA tags for Knura over a PC/SC reader. */
public final class Main {
    static final String USAGE = """
            usage: knurctl <command> [options]

            commands:
              readers               list NFC readers
              init                  create knurd.properties with a new random master key
              info                  show a tag's UID, URL and whose keys it has
              provision             set a tag up: Knura keys and a tap URL that knurd verifies
              tap                   read the tag like a phone does and print its tap URL
              reset                 put a Knura tag back to factory state (all keys zero)
              resolver register     have tap.knura.io forward your tags' taps to this knurd
                                    (run once to create your owner ID, restart knurd, run again;
                                    run again whenever public.url changes)
              resolver check        show where tap.knura.io sends your taps

            knurd's admin API (its own port, on this computer or your local network only):
              admin token add NAME  make a login token for knurctl, LoomStudio...; writes it to a
                                    command config file (default knurctl-admin.properties).
                                    The first time, also turns the admin API on: restart knurd
              admin token list      list token names          admin token remove NAME
              admin tls create      make the TLS certificate the admin API needs for LAN use
              admin whoami          check the command config logs in
              admin status          knurd's uptime, keys mode, public URL, knots and tags
              admin tags            every tag knurd has accepted, with its last counter
              admin taps            the latest taps and how each went (--limit N, default 20)

            options:
              --config FILE         settings file holding master.key (default knurd.properties)
              --base-url URL        provision: where taps go. Default: the resolver link
                                    (resolver.url/resolver.owner) after 'resolver register',
                                    else public.url + /tap, else http://127.0.0.1:<listen.port>/tap
              --server URL          resolver register: where taps go (default public.url)
              --resolver URL        resolver register: the resolver (default https://tap.knura.io)
              --from SOURCE         the keys the tag has now: factory (provision default),
                                    knura (set up by Knura with this master key; reset default),
                                    or a file with key0= to key4= lines
              --reader N|NAME       reader number from 'knurctl readers', or part of its name
              --wait SECONDS        how long to wait for a tag (default 30)
              --verbose             print every command sent to the tag
              --command-config FILE admin: knurd's admin URL and token (default
                                    knurctl-admin.properties). Must be chmod 600
              --admin-url URL       admin token add: the address to put in the command config
              --out FILE            admin token add: where to write the command config
              --limit N             admin taps: how many to show
            """;

    private static final Set<String> FLAGS = Set.of("--verbose", "--help");
    private static final Set<String> OPTIONS =
            Set.of("--config", "--base-url", "--from", "--reader", "--wait", "--server", "--resolver",
                    "--command-config", "--admin-url", "--out", "--limit");

    private final PrintStream out;
    private final Map<String, String> options;

    private Main(PrintStream out, Map<String, String> options) {
        this.out = out;
        this.options = options;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = new HashMap<>();
        List<String> words = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (FLAGS.contains(a)) {
                options.put(a, "true");
            } else if (OPTIONS.contains(a) && i + 1 < args.length) {
                options.put(a, args[++i]);
            } else if (!a.startsWith("-") && (words.isEmpty() || takesWords(words.get(0), words.size()))) {
                words.add(a);
            } else {
                err.println("knurctl: unexpected argument: " + a);
                err.print(USAGE);
                return 2;
            }
        }
        String command = words.isEmpty() ? null : words.get(0);
        String subcommand = words.size() > 1 ? words.get(1) : null;
        if (command == null || options.containsKey("--help")) {
            out.print(USAGE);
            return command == null && !options.containsKey("--help") ? 2 : 0;
        }
        Main main = new Main(out, options);
        try {
            switch (command) {
                case "readers" -> main.readers();
                case "init" -> main.init();
                case "info" -> main.info();
                case "provision" -> main.provision();
                case "tap" -> main.tap();
                case "reset" -> main.reset();
                case "admin" -> new AdminCommands(out, options, main.configPath()).run(words);
                case "resolver" -> {
                    if ("register".equals(subcommand)) {
                        main.resolverRegister();
                    } else if ("check".equals(subcommand)) {
                        main.resolverCheck();
                    } else {
                        err.println("knurctl: use 'resolver register' or 'resolver check'");
                        return 2;
                    }
                }
                default -> {
                    err.println("knurctl: unknown command: " + command);
                    err.print(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (UsageException e) {
            err.println("knurctl: " + e.getMessage());
            return 2;
        } catch (ReaderException | IOException e) {
            err.println("knurctl: " + e.getMessage());
            return 1;
        }
    }

    /** Whether {@code command} takes another word, having {@code count} words so far. */
    private static boolean takesWords(String command, int count) {
        return switch (command) {
            case "resolver" -> count < 2;
            case "admin" -> count < 4;
            default -> false;
        };
    }

    private void readers() throws ReaderException {
        List<CardTerminal> readers = PcscReader.readers();
        if (readers.isEmpty()) {
            out.println("No NFC readers found. Is the reader plugged in, and is pcscd running?");
            return;
        }
        for (int i = 0; i < readers.size(); i++) {
            CardTerminal t = readers.get(i);
            String tag;
            try {
                tag = t.isCardPresent() ? "  (tag present)" : "";
            } catch (CardException e) {
                tag = "";
            }
            out.printf("%d. %s%s%n", i + 1, t.getName(), tag);
        }
    }

    private void init() throws IOException, UsageException {
        Path config = configPath();
        byte[] master = new byte[16];
        new SecureRandom().nextBytes(master);
        String content = """
                # Settings for knurd and knurctl. Keep this file secret: master.key unlocks every
                # tag set up with it. If you lose it, tags set up with it can only be verified or
                # reset with a backup of this file.
                listen.host=127.0.0.1
                listen.port=8424
                # The HTTPS address phones use to reach knurd, e.g. a Tailscale Funnel name.
                # knurctl provision writes <public.url>/tap onto tags, or a tap.knura.io link that
                # forwards there once you run: knurctl resolver register
                #public.url=https://your-computer.your-tailnet.ts.net
                master.key=%s
                counters.file=knurd-counters.properties
                """.formatted(Hex.encode(master));
        try {
            Files.writeString(config, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw new UsageException(config + " already exists; knurctl will not overwrite a master key."
                    + " Delete it first only if no tag uses its key.");
        }
        try {
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system: leave the default permissions.
        }
        out.println("Wrote " + config.toAbsolutePath() + " with a new master key.");
        out.println("Back this file up somewhere safe: tags you set up depend on its master.key.");
    }

    private void info() throws IOException, ReaderException, UsageException {
        byte[] master = masterKeyIfConfigured();
        try (PcscReader reader = connect()) {
            byte[] uid = reader.uid();
            out.println("UID:  " + Hex.encode(uid));
            Ntag424 tag = new Ntag424(reader);
            String url = null;
            try {
                url = TagProvisioner.readTapUrl(tag);
                out.println("URL:  " + url);
            } catch (Ntag424Exception e) {
                out.println("URL:  none (" + e.getMessage() + ")");
            }
            if (tryKey0(tag, Ntag424.FACTORY_KEY)) {
                out.println("Keys: factory (ready to provision)");
            } else if (master != null && tryKey0(tag, TagKeySet.knura(master, uid).key(0))) {
                out.println("Keys: Knura, from the master key in " + configPath());
                if (url != null) {
                    printCheck(url, TagKeySet.knura(master, uid));
                }
            } else {
                out.println("Keys: not factory" + (master == null ? "" : ", and not from this master key")
                        + " (set up by other software, or with another master key)");
            }
        }
    }

    private void provision() throws IOException, ReaderException, UsageException {
        byte[] master = masterKey();
        Properties config = config();
        TapUrlLayout layout;
        try {
            layout = TapUrlLayout.forBaseUrl(options.getOrDefault("--base-url", defaultBaseUrl(config)));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        if (!options.containsKey("--base-url") && hasResolver(config)) {
            warnUnlessForwarding(config);
        }
        String from = options.getOrDefault("--from", "factory");
        try (PcscReader reader = connect()) {
            byte[] uid = reader.uid();
            TagKeySet current = currentKeys(from, master, uid);
            TagKeySet target = TagKeySet.knura(master, uid);
            TagProvisioner provisioner = new TagProvisioner(line -> out.println("  " + line));
            TagProvisioner.Result result;
            try {
                result = provisioner.provision(new Ntag424(reader), uid, current, target, layout);
            } catch (Ntag424Exception e) {
                throw explainKeyMismatch(e, from);
            }
            out.println();
            out.println("Provisioned tag " + result.uidHex() + ".");
            out.println("It read back as: " + result.tapUrl());
            out.println("(UID " + result.tap().uidHex() + ", counter " + result.tap().readCounter() + ", MAC checks out)");
            out.println();
            out.println("To check it with knurd (start knurd first):");
            out.println("  curl '" + result.tapUrl() + "'");
            if (isLocalOnly(layout.baseUrl())) {
                out.println();
                out.println("Phones can't open " + layout.baseUrl() + ". To tap with a phone, set public.url in "
                        + configPath() + " and provision again with --from knura.");
            }
        }
    }

    private void tap() throws IOException, ReaderException, UsageException {
        byte[] master = masterKeyIfConfigured();
        try (PcscReader reader = connect()) {
            byte[] uid = reader.uid();
            String url = TagProvisioner.readTapUrl(new Ntag424(reader));
            out.println(url);
            if (master != null) {
                printCheck(url, TagKeySet.knura(master, uid));
            }
        }
    }

    private void reset() throws IOException, ReaderException, UsageException {
        String from = options.getOrDefault("--from", "knura");
        byte[] master = "knura".equals(from) ? masterKey() : null;
        try (PcscReader reader = connect()) {
            byte[] uid = reader.uid();
            TagKeySet current = currentKeys(from, master, uid);
            try {
                new TagProvisioner(line -> out.println("  " + line)).reset(new Ntag424(reader), uid, current);
            } catch (Ntag424Exception e) {
                throw explainKeyMismatch(e, from);
            }
            out.println("Tag " + Hex.encode(uid) + " is back to factory state: all keys zero, SDM off, NDEF empty.");
        }
    }

    private void printCheck(String url, TagKeySet keys) {
        try {
            VerifiedTap tap = TagProvisioner.verifyTapUrl(
                    url, keys.key(TagKeyDerivation.SDM_META_READ_KEY), keys.key(TagKeyDerivation.SDM_FILE_READ_KEY));
            out.println("Check: genuine, UID " + tap.uidHex() + ", counter " + tap.readCounter());
        } catch (SdmVerificationException e) {
            out.println("Check: does not verify with this master key (" + e.getMessage() + ")");
        }
    }

    private boolean tryKey0(Ntag424 tag, byte[] key) throws IOException {
        tag.selectApplication();
        try {
            tag.authenticate(0, key);
            return true;
        } catch (Ntag424Exception e) {
            if (e.isAuthenticationError()) {
                return false;
            }
            throw e;
        } finally {
            tag.endSession();
        }
    }

    private TagKeySet currentKeys(String from, byte[] master, byte[] uid) throws IOException, UsageException {
        return switch (from) {
            case "factory" -> TagKeySet.factory();
            case "knura" -> TagKeySet.knura(master, uid);
            default -> KeyFile.load(Path.of(from));
        };
    }

    private static IOException explainKeyMismatch(Ntag424Exception e, String from) {
        if (!e.isAuthenticationError()) {
            return e;
        }
        String hint = switch (from) {
            case "factory" -> "This tag no longer has factory keys. If Knura set it up with this master"
                    + " key, use --from knura; if other software did, use --from <keys file>.";
            case "knura" -> "This tag's key 0 is not the Knura key from this master key. It may have"
                    + " factory keys (try knurctl info), another master key, or keys from other software.";
            default -> "This tag's key 0 does not match key0 in " + from + ".";
        };
        return new IOException("the tag refused the key: " + hint, e);
    }

    private PcscReader connect() throws ReaderException, UsageException {
        int seconds;
        try {
            seconds = Integer.parseInt(options.getOrDefault("--wait", "30"));
        } catch (NumberFormatException e) {
            throw new UsageException("--wait must be a number of seconds");
        }
        PrintStream trace = options.containsKey("--verbose") ? out : null;
        return PcscReader.connect(options.get("--reader"), Duration.ofSeconds(seconds), out, trace);
    }

    private Path configPath() {
        return Path.of(options.getOrDefault("--config", "knurd.properties"));
    }

    private Properties config() throws IOException {
        Properties p = new Properties();
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(in);
            }
        }
        return p;
    }

    private byte[] masterKey() throws IOException, UsageException {
        byte[] master = masterKeyIfConfigured();
        if (master == null) {
            throw new UsageException("no master.key in " + configPath().toAbsolutePath()
                    + "; create one with: knurctl init");
        }
        return master;
    }

    private byte[] masterKeyIfConfigured() throws IOException, UsageException {
        String hex = config().getProperty("master.key");
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            byte[] key = Hex.decode(hex.trim());
            if (key.length == 16) {
                return key;
            }
        } catch (IllegalArgumentException e) {
            // Reported below.
        }
        throw new UsageException("master.key in " + configPath() + " must be 32 hex digits");
    }

    /** Where provision points tags when --base-url isn't given. */
    static String defaultBaseUrl(Properties config) {
        if (hasResolver(config)) {
            return resolverClient(config).tapBase(config.getProperty("resolver.owner").trim());
        }
        String publicUrl = publicUrl(config);
        if (publicUrl != null) {
            return publicUrl + "/tap";
        }
        String port = config.getProperty("listen.port", "8424").trim();
        return "http://127.0.0.1:" + port + "/tap";
    }

    private static String publicUrl(Properties config) {
        String publicUrl = config.getProperty("public.url", "").trim();
        if (publicUrl.isEmpty()) {
            return null;
        }
        return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    private static boolean hasResolver(Properties config) {
        return !config.getProperty("resolver.owner", "").isBlank();
    }

    private static ResolverClient resolverClient(Properties config) {
        return new ResolverClient(config.getProperty("resolver.url", ResolverClient.DEFAULT_URL).trim());
    }

    /**
     * Registers this knurd with the resolver. The first run only creates an owner ID and key and
     * adds them to the settings file, because the resolver asks knurd to confirm the owner ID
     * before it forwards anything, and knurd reads it only when it starts.
     */
    private void resolverRegister() throws IOException, UsageException {
        Path path = configPath();
        if (!Files.exists(path)) {
            throw new UsageException(path.toAbsolutePath() + " not found; create it with: knurctl init");
        }
        Properties config = config();
        if (!hasResolver(config)) {
            ResolverClient client = newResolverClient(options.getOrDefault("--resolver", ResolverClient.DEFAULT_URL));
            String owner = OwnerIds.generate(new SecureRandom());
            OwnerKey key = OwnerKey.generate();
            String lines = """

                    # Tap resolver (knurctl resolver register): tags provisioned after registering
                    # carry %s/<resolver.owner>, and the resolver forwards each tap to public.url.
                    # resolver.key proves you own resolver.owner: keep it secret, back it up, and
                    # never share it. Whoever has it can send your tags' taps elsewhere.
                    resolver.url=%s
                    resolver.owner=%s
                    resolver.publicKey=%s
                    resolver.key=%s
                    """.formatted(client.baseUrl(), client.baseUrl(), owner, key.publicKeyHex(), key.seedHex());
            String existing = Files.readString(path, StandardCharsets.UTF_8);
            String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
            Files.writeString(path, separator + lines, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            out.println("Your owner ID is " + owner + ". Tags will carry " + client.tapBase(owner) + "?e=...&c=...");
            out.println("Added it and its key to " + path.toAbsolutePath() + " (back this file up).");
            out.println();
            out.println("Next: restart knurd so it can confirm the owner ID to the resolver, then run");
            out.println("  knurctl resolver register");
            out.println("again to finish.");
            return;
        }

        if (options.containsKey("--resolver")) {
            throw new UsageException("this knurd is already set up for " + config.getProperty("resolver.url")
                    + "; --resolver only applies the first time");
        }
        String owner = owner(config);
        OwnerKey key;
        try {
            key = OwnerKey.load(config.getProperty("resolver.key", ""), config.getProperty("resolver.publicKey", ""));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + " (in " + path + ")");
        }
        ResolverClient client = configuredResolver(config);
        String server = options.getOrDefault("--server", publicUrl(config));
        if (server == null) {
            throw new UsageException("set public.url in " + path + " (or pass --server): it's where taps go");
        }
        String problem = OwnerRecord.checkServer(server, client.isLocal());
        if (problem != null) {
            throw new UsageException(problem + ": " + server);
        }

        Optional<OwnerRecord> current = client.fetch(owner);
        if (current.isPresent() && !key.hasPublicKey(current.get().publicKey())) {
            throw new IOException(client.baseUrl() + " has owner " + owner + " under another key. If you didn't"
                    + " register it with another resolver.key, see knura-resolver/README.md.");
        }
        long seq = Math.max(System.currentTimeMillis(), current.map(r -> r.seq() + 1).orElse(1L));
        client.put(OwnerRecord.sign(owner, server, seq, key));
        out.println(client.baseUrl() + " now sends taps for " + owner + " to " + server + "/tap.");
        out.println("Tags you provision from now on get " + client.tapBase(owner) + "?e=...&c=...");
        out.println("Tags provisioned before still point wherever they did; provision them again with --from knura.");
    }

    private void resolverCheck() throws IOException, UsageException {
        Properties config = config();
        if (!hasResolver(config)) {
            throw new UsageException("no resolver.owner in " + configPath().toAbsolutePath()
                    + "; set one up with: knurctl resolver register");
        }
        ResolverCheck.Result result = checkForwarding(config);
        if (!result.ok()) {
            throw new IOException(result.message());
        }
        out.println(result.message() + "/tap, signed with your owner key. All good.");
    }

    private ResolverCheck.Result checkForwarding(Properties config) throws IOException, UsageException {
        String owner = owner(config);
        String publicUrl = publicUrl(config);
        if (publicUrl == null) {
            throw new UsageException("set public.url in " + configPath() + ": it's where the resolver should send taps");
        }
        return ResolverCheck.check(configuredResolver(config).fetch(owner), owner,
                config.getProperty("resolver.publicKey", "").trim(), publicUrl);
    }

    /** Warns, without stopping provision, when the resolver doesn't send this owner's taps here yet. */
    private void warnUnlessForwarding(Properties config) throws UsageException {
        try {
            ResolverCheck.Result result = checkForwarding(config);
            if (!result.ok()) {
                out.println("Warning: " + result.message() + ". The tag will work once 'knurctl resolver register'"
                        + " succeeds; it won't need rewriting.");
            }
        } catch (IOException e) {
            out.println("Warning: could not check the resolver (" + e.getMessage() + ").");
        }
    }

    private String owner(Properties config) throws UsageException {
        String owner = config.getProperty("resolver.owner", "").trim();
        if (!OwnerIds.isValid(owner)) {
            throw new UsageException("resolver.owner in " + configPath() + " is not a valid owner ID");
        }
        return owner;
    }

    private static ResolverClient configuredResolver(Properties config) throws UsageException {
        return newResolverClient(config.getProperty("resolver.url", ResolverClient.DEFAULT_URL).trim());
    }

    private static ResolverClient newResolverClient(String url) throws UsageException {
        try {
            return new ResolverClient(url);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    private static boolean isLocalOnly(String baseUrl) {
        String host = URI.create(baseUrl).getHost();
        return host == null || host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]");
    }

    /** Bad command line or settings. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
