package io.knura.knurctl;

import io.knura.core.crypto.Hex;
import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.ntag424.Ntag424;
import io.knura.core.ntag424.Ntag424Exception;
import io.knura.core.ntag424.TapUrlLayout;
import io.knura.core.provision.TagKeySet;
import io.knura.core.provision.TagProvisioner;
import io.knura.core.sdm.SdmVerificationException;
import io.knura.core.sdm.VerifiedTap;
import io.knura.knurctl.PcscReader.ReaderException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

            options:
              --config FILE         settings file holding master.key (default knurd.properties)
              --base-url URL        provision: where taps go (default http://127.0.0.1:8424/tap,
                                    using listen.port from the settings file)
              --from SOURCE         the keys the tag has now: factory (provision default),
                                    knura (set up by Knura with this master key; reset default),
                                    or a file with key0= to key4= lines
              --reader N|NAME       reader number from 'knurctl readers', or part of its name
              --wait SECONDS        how long to wait for a tag (default 30)
              --verbose             print every command sent to the tag
            """;

    private static final Set<String> FLAGS = Set.of("--verbose", "--help");
    private static final Set<String> OPTIONS =
            Set.of("--config", "--base-url", "--from", "--reader", "--wait");

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
        String command = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (FLAGS.contains(a)) {
                options.put(a, "true");
            } else if (OPTIONS.contains(a) && i + 1 < args.length) {
                options.put(a, args[++i]);
            } else if (!a.startsWith("-") && command == null) {
                command = a;
            } else {
                err.println("knurctl: unexpected argument: " + a);
                err.print(USAGE);
                return 2;
            }
        }
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
        TapUrlLayout layout;
        try {
            layout = TapUrlLayout.forBaseUrl(options.getOrDefault("--base-url", defaultBaseUrl()));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
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

    private String defaultBaseUrl() throws IOException {
        String port = config().getProperty("listen.port", "8424").trim();
        return "http://127.0.0.1:" + port + "/tap";
    }

    /** Bad command line or settings. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
