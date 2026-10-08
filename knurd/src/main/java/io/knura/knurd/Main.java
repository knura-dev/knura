package io.knura.knurd;

import io.knura.core.resolver.ResolverClient;
import io.knura.knurgn.Knurgn;
import io.knura.knurgn.KnurgnConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

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
        TapVerifier verifier = new TapVerifier(config.keys(), new FileCounterStore(config.countersFile()));
        KnurdServer.TapListener listener = knurgn == null ? (uid, counter) -> 0 : knurgn::onVerifiedTap;
        KnurdConfig.Resolver resolver = config.resolver();
        KnurdServer server = new KnurdServer(
                config.host(), config.port(), verifier, listener, resolver == null ? null : resolver.owner());
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (resolverWatch != null) {
                resolverWatch.close();
            }
            server.close();
            if (actions != null) {
                actions.close();
            }
        }));
    }
}
