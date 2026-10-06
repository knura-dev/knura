package io.knura.knurd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        TapVerifier verifier = new TapVerifier(config.keys(), new FileCounterStore(config.countersFile()));
        KnurdServer server = new KnurdServer(config.host(), config.port(), verifier);
        server.start();
        System.out.printf(
                "knurd listening on http://%s:%d (counters in %s)%n",
                config.host(), server.port(), config.countersFile());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
