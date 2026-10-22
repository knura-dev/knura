package io.knura.knurd;

import io.knura.core.admin.AdminKeystore;
import io.knura.core.resolver.ResolverClient;
import io.knura.knurgn.Knurgn;
import io.knura.knurgn.KnurgnConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Starts knurd: {@code knurd [config-file]}, default {@code knurd.properties}. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length > 1 || (args.length == 1 && args[0].startsWith("-"))) {
            System.err.println("usage: knurd [config-file]   (default: knurd.properties)");
            System.exit(2);
        }
        Path configFile = Path.of(args.length == 1 ? args[0] : "knurd.properties");
        if (!Files.exists(configFile)) {
            System.err.println("knurd: config file not found: " + configFile.toAbsolutePath());
            System.err.println("Copy knurd/knurd.example.properties to knurd.properties and set your keys.");
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
        KnurdServer.TapListener listener = knurgn == null ? (uid, counter) -> 0 : knurgn::onVerifiedTap;
        KnurdConfig.Resolver resolver = config.resolver();
        KnurdServer server = new KnurdServer(
                config.host(), config.port(), verifier, listener, resolver == null ? null : resolver.owner());
        RecentTaps recentTaps = new RecentTaps(Clock.systemUTC());
        server.recordTapsTo(recentTaps);
        AdminServer admin = null;
        if (config.admin() != null) {
            try {
                admin = startAdmin(config, counters, recentTaps, knurgn == null ? 0 : knurgn.knotCount());
            } catch (IOException e) {
                server.close();
                System.err.println("knurd: can't start the admin API: " + e.getMessage());
                System.exit(1);
                return;
            }
        }
        server.start();
        System.out.printf(
                "knurd listening on http://%s:%d (counters in %s)%n",
                config.host(), server.port(), config.countersFile());
        if (config.publicUrl() != null) {
            System.out.printf("Phones reach knurd at %s/tap%n", config.publicUrl());
        } else {
            System.out.println("public.url is not set, so phones can't reach knurd yet"
                    + " (see knurd.example.properties).");
        }
        ResolverWatch watch = null;
        if (resolver != null) {
            System.out.printf("Tags with %s/%s links reach this knurd through the resolver"
                    + " (checked now and every %d hours)%n",
                    resolver.url(), resolver.owner(), ResolverWatch.EVERY.toHours());
            ResolverClient client = new ResolverClient(resolver.url());
            watch = new ResolverWatch(resolver, config.publicUrl(), client::fetch);
            watch.start();
        }
        if (knurgn != null) {
            System.out.printf("knurgN: %d knot(s) with actions, from %s%n", knurgn.knotCount(), config.actionsFile());
        }
        Knurgn actions = knurgn;
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
            if (actions != null) {
                actions.close();
            }
        }));
    }

    /** Starts the admin listener; see {@link AdminServer}. */
    private static AdminServer startAdmin(KnurdConfig config, CounterStore counters, RecentTaps taps, int knots)
            throws IOException {
        KnurdConfig.Admin a = config.admin();
        SSLContext tls = null;
        String fingerprint = null;
        if (a.tls()) {
            AdminKeystore keystore = AdminKeystore.load(a.keystore(), a.passwordFile());
            try {
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keystore.keyStore(), keystore.password());
                tls = SSLContext.getInstance("TLS");
                tls.init(kmf.getKeyManagers(), null, null);
            } catch (GeneralSecurityException e) {
                throw new IOException("can't use the TLS key in " + a.keystore() + ": " + e.getMessage(), e);
            } finally {
                Arrays.fill(keystore.password(), '\0');
            }
            fingerprint = keystore.fingerprint();
        }
        TokenStore tokens = a.plaintext() ? null : new TokenStore(a.tokensFile());
        AuditLog audit = new AuditLog(a.auditFile(), Clock.systemUTC());
        KnurdConfig.Resolver resolver = config.resolver();
        AdminServer.Info info = new AdminServer.Info(config.keys().mode(), config.publicUrl(),
                resolver == null ? null : resolver.owner(), knots, Instant.now());
        NfcRelay nfc = null;
        if (!a.provisionTokens().isEmpty()) {
            String tapBase = resolver == null
                    ? config.publicUrl() + "/tap"
                    : new ResolverClient(resolver.url()).tapBase(resolver.owner());
            nfc = new NfcRelay(config.keys(), tapBase, a.provisionTokens(), NfcRelay.REPLY_WAIT);
        }
        AdminServer admin = new AdminServer(
                a.host(), a.port(), tls, tokens, audit, counters, taps, info, Clock.systemUTC(), nfc);
        admin.start();
        System.out.printf("knurd admin API on %s://%s:%d, for knurctl only: never put this port on the internet%n",
                a.tls() ? "https" : "http", a.host(), admin.port());
        if (fingerprint != null) {
            System.out.println("Admin API certificate fingerprint (SHA-256): " + fingerprint);
        }
        if (nfc != null) {
            System.out.println("Phones may set tags up with these admin tokens: "
                    + String.join(", ", a.provisionTokens().stream().sorted().toList()));
        }
        if (tokens == null) {
            System.out.printf("Admin API is in plaintext mode: no login, this computer only. Every request is"
                    + " logged to %s%n", a.auditFile());
        } else {
            System.out.printf("Admin tokens: %d in %s; every request is logged to %s%n",
                    tokens.count(), a.tokensFile(), a.auditFile());
        }
        return admin;
    }
}
