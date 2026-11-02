package io.knura.knurd;

import io.knura.core.admin.AdminKeystore;
import io.knura.core.backup.BackupRecord;
import io.knura.core.log.Logs;
import io.knura.core.resolver.ResolverClient;
import io.knura.knurgn.Knurgn;
import io.knura.knurgn.KnurgnConfig;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/** Starts knurd: {@code knurd [config-file]}, default {@code knurd.properties}. */
public final class Main {
    private static final System.Logger LOG = System.getLogger("knurd");

    private Main() {}

    public static void main(String[] args) throws IOException {
        Path configFile;
        boolean lan = false;
        if (args.length >= 2 && args[0].equals("--home") && (args.length == 2 || args.length == 3 && args[2].equals("--lan"))) {
            // How the installers start knurd: the folder, and its settings on the first start.
            lan = args.length == 3;
            try {
                configFile = FirstRun.prepare(Path.of(args[1]), lan);
            } catch (IOException e) {
                System.err.println("knurd: can't set up the settings folder " + args[1] + ": " + e.getMessage());
                System.exit(1);
                return;
            }
        } else if (args.length > 1 || (args.length == 1 && args[0].startsWith("-"))) {
            System.err.println("usage: knurd [config-file]   (default: knurd.properties)");
            System.err.println("       knurd --home <folder> [--lan]   (makes the folder's settings on the first start)");
            System.exit(2);
            return;
        } else {
            configFile = Path.of(args.length == 1 ? args[0] : "knurd.properties");
        }
        if (!Files.exists(configFile)) {
            System.err.println("knurd: config file not found: " + configFile.toAbsolutePath());
            System.err.println("Copy knurd/knurd.example.properties to knurd.properties and set your keys.");
            System.exit(1);
        }
        if (!Logs.setUp("knurd", configFile)) {
            System.exit(1);
        }
        KnurdConfig config;
        try {
            config = KnurdConfig.load(configFile);
        } catch (IllegalArgumentException e) {
            System.err.println("knurd: bad config: " + e.getMessage());
            System.exit(1);
            return;
        }
        Knurgn knurgn = null;
        if (config.actionsFile() != null) {
            if (!Files.exists(config.actionsFile())) {
                System.err.println("knurd: actions file not found: " + config.actionsFile());
                System.exit(1);
            }
            try {
                knurgn = KnurgnConfig.load(config.actionsFile(), Clock.systemUTC());
            } catch (IllegalArgumentException e) {
                System.err.println("knurd: bad actions file " + config.actionsFile() + ": " + e.getMessage());
                System.exit(1);
                return;
            }
        }
        FileCounterStore counters = new FileCounterStore(config.countersFile());
        TapVerifier verifier = new TapVerifier(config.keys(), counters);
        LiveActions listener = new LiveActions(knurgn, Clock.systemUTC());
        HomeSettings settings = new HomeSettings(configFile, config.actionsFile(), listener, Clock.systemUTC());
        KnurdConfig.Resolver resolver = config.resolver();
        KnurdServer server = new KnurdServer(
                config.host(), config.port(), verifier, listener, resolver == null ? null : resolver.owner());
        // With the admin API on, the dashboard shows tap history, so keep it across restarts.
        RecentTaps recentTaps = config.admin() == null ? new RecentTaps(Clock.systemUTC())
                : new RecentTaps(Clock.systemUTC(), configFile.toAbsolutePath().getParent().resolve(RecentTaps.FILE));
        server.recordTapsTo(recentTaps);
        AdminServer admin = null;
        if (config.admin() != null) {
            try {
                admin = startAdmin(config, configFile, counters, recentTaps, knurgn == null ? 0 : knurgn.knotCount(),
                        settings);
            } catch (IOException e) {
                server.close();
                System.err.println("knurd: can't start the admin API: " + e.getMessage());
                System.exit(1);
                return;
            }
        }
        server.start();
        info(
                "knurd listening on http://%s:%d (counters in %s)",
                config.host(), server.port(), config.countersFile());
        if (config.publicUrl() != null) {
            info("Phones reach knurd at %s/tap", config.publicUrl());
        } else {
            info("public.url is not set, so phones can't reach knurd yet"
                    + " (see knurd.example.properties).");
        }
        ResolverWatch watch = null;
        if (resolver != null) {
            info("Tags with %s/%s links reach this knurd through the resolver"
                    + " (checked now and every %d hours)",
                    resolver.url(), resolver.owner(), ResolverWatch.EVERY.toHours());
            ResolverClient client = new ResolverClient(resolver.url());
            watch = new ResolverWatch(resolver, config.publicUrl(), client::fetch);
            watch.start();
        }
        if (knurgn != null) {
            info("knurgN: %d knot(s) with actions, from %s", knurgn.knotCount(), config.actionsFile());
        }
        Supplier<BackupRecord.Status> backup = backupStatus(config, configFile);
        if (backup != null && !backup.get().any()) {
            LOG.log(Level.WARNING, "master.key has no backup yet, and every tag set up with it depends on it."
                    + " Run 'knurctl backup create' or 'knurctl backup sheet' in " + configFile.toAbsolutePath().getParent());
        }
        Path clone = io.knura.core.backup.SettingsFolder.codeClone(configFile.toAbsolutePath().getParent());
        if (clone != null) {
            LOG.log(Level.WARNING, "the settings folder " + configFile.toAbsolutePath().getParent() + " is inside the code folder "
                    + clone + ". Deleting that folder to download Knura again would delete your master key and"
                    + " settings too. Move the settings folder somewhere else, like ~/knura-home.");
        }
        LiveActions actions = listener;
        ResolverWatch resolverWatch = watch;
        AdminServer adminServer = admin;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (adminServer != null) {
                adminServer.close();
            }
            if (resolverWatch != null) {
                resolverWatch.close();
            }
            server.close();
            actions.close();
        }));
    }

    /** Reads when this master key was last backed up, or null without a master key. */
    static Supplier<BackupRecord.Status> backupStatus(KnurdConfig config, Path configFile) {
        Optional<String> id = config.keys().masterKeyId();
        if (id.isEmpty()) {
            return null;
        }
        Path folder = configFile.toAbsolutePath().getParent();
        return () -> BackupRecord.read(folder, id.get());
    }

    /** Starts the admin listener; see {@link AdminServer}. */
    private static AdminServer startAdmin(KnurdConfig config, Path configFile, CounterStore counters, RecentTaps taps,
            int knots, HomeSettings settings)
            throws IOException {
        KnurdConfig.Admin a = config.admin();
        SSLContext tls = null;
        SSLContext wifiTls = null;
        String fingerprint = null;
        if (a.keystore() != null) {
            AdminKeystore keystore = AdminKeystore.load(a.keystore(), a.passwordFile());
            fingerprint = keystore.fingerprint();
            SSLContext context = PhonePairing.tlsContext(keystore);
            if (a.lanPort() > 0) {
                wifiTls = context;
            } else {
                tls = context;
            }
        }
        TokenStore tokens = a.plaintext() ? null : new TokenStore(a.tokensFile());
        AuditLog audit = new AuditLog(a.auditFile(), Clock.systemUTC());
        KnurdConfig.Resolver resolver = config.resolver();
        AdminServer.Info info = new AdminServer.Info(config.keys().mode(), config.publicUrl(),
                resolver == null ? null : resolver.owner(), knots, Instant.now(), backupStatus(config, configFile));
        NfcRelay nfc = null;
        if (!a.provisionTokens().isEmpty()) {
            String tapBase = resolver == null
                    ? config.publicUrl() + "/tap"
                    : new ResolverClient(resolver.url()).tapBase(resolver.owner());
            nfc = new NfcRelay(config.keys(), tapBase, a.provisionTokens(), NfcRelay.REPLY_WAIT);
        }
        AdminServer admin = new AdminServer(
                a.host(), a.port(), tls, tokens, audit, counters, taps, info, Clock.systemUTC(), nfc);
        AdminPassword password = new AdminPassword(configFile.toAbsolutePath().getParent().resolve(AdminPassword.FILE));
        PhonePairing phones = new PhonePairing(config.keys(), resolver, a, tokens, settings, fingerprint, Clock.systemUTC());
        phones.attach(admin);
        admin.setBrowserOnWifi(settings.browserOnWifi());
        SetupCode setupCode = new SetupCode(configFile.toAbsolutePath().getParent().resolve(SetupCode.FILE));
        admin.manage(new AdminServer.Manage(password, new Sessions(Clock.systemUTC()), settings,
                Tailscale.find(config.port()), phones, setupCode));
        admin.start();
        if (wifiTls != null) {
            try {
                admin.listenOnWifi(a.lanPort(), wifiTls);
            } catch (IOException e) {
                admin.close();
                throw new IOException("can't listen on admin.lan.port " + a.lanPort() + ": " + e.getMessage(), e);
            }
            info("knurd admin API also on https://0.0.0.0:%d for phones on your home network (admin.lan.port)",
                    a.lanPort());
            if (!password.isSet()) {
                // No screen on this computer, perhaps: let the first password come from another one.
                String code = setupCode.ensure();
                java.util.List<String> here = io.knura.core.admin.LocalAddresses.homeNetworkAddresses();
                info("No setup page password yet. On a computer or phone on the same network, open https://%s:%d/setup"
                        + " (your browser will warn about the certificate; continue) and enter the setup code %s",
                        here.isEmpty() ? "<this computer's address>" : here.get(0), a.lanPort(), code);
            }
        }
        info("knurd admin API on %s://%s:%d, for knurctl only: never put this port on the internet",
                a.tls() ? "https" : "http", a.host(), admin.port());
        if (fingerprint != null) {
            // In pairs, as knurctl accepts it: a long unbroken hex run would be hidden as if it were a key.
            info("Admin API certificate fingerprint (SHA-256): " + String.join(":", fingerprint.split("(?<=\\G..)")));
        }
        if (nfc != null) {
            info("Phones may set tags up with these admin tokens: "
                    + String.join(", ", a.provisionTokens().stream().sorted().toList()));
        }
        if (tokens == null) {
            info("Admin API is in plaintext mode: no login, this computer only. Every request is"
                    + " logged to %s", a.auditFile());
        } else {
            info("Admin tokens: %d in %s; every request is logged to %s",
                    tokens.count(), a.tokensFile(), a.auditFile());
        }
        return admin;
    }

    /** Startup messages go through the logger too, so they land in log.file as well. */
    private static void info(String format, Object... args) {
        LOG.log(Level.INFO, args.length == 0 ? format : String.format(format, args));
    }
}
