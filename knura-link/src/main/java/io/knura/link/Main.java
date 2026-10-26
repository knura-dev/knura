package io.knura.link;

import io.knura.knurgn.Knurgn;
import io.knura.knurgn.KnurgnConfig;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * The home link: {@code knura-link [config-file]} runs it, {@code knura-link pair [config-file]}
 * pairs it with a code from a hosted Knura account's dashboard. The default config file is {@code
 * link.properties} in the current folder.
 */
public final class Main {
    static final String DEFAULT_CONFIG = "link.properties";

    private Main() {}

    public static void main(String[] args) throws IOException {
        boolean pair = args.length > 0 && args[0].equals("pair");
        int rest = pair ? args.length - 1 : args.length;
        if (rest > 1 || (args.length > 0 && args[args.length - 1].startsWith("-"))) {
            System.err.println("usage: knura-link [config-file]        run the home link (default: " + DEFAULT_CONFIG + ")");
            System.err.println("       knura-link pair [config-file]   pair it with a code from your account's dashboard");
            System.exit(2);
        }
        Path configFile = Path.of(rest == 1 ? args[args.length - 1] : DEFAULT_CONFIG);
        System.exit(pair ? pair(configFile) : run(configFile));
    }

    private static int pair(Path configFile) throws IOException {
        String code = readCode();
        try {
            PairingCode pairing = PairingCode.parse(code);
            LinkConfig.save(configFile, pairing);
            System.out.println("Paired with " + pairing.url().getHost() + " (account " + pairing.owner() + ").");
        } catch (IllegalArgumentException e) {
            System.err.println("knura-link: " + e.getMessage());
            return 1;
        }
        Path actions = configFile.toAbsolutePath().getParent().resolve(LinkConfig.DEFAULT_ACTIONS_FILE);
        if (!Files.exists(actions)) {
            System.out.println("Next, write your tag rules in " + actions + " (see docs/home-link.md), then run knura-link.");
        } else {
            System.out.println("Your tag rules are in " + actions + ". Start the home link with: knura-link");
        }
        return 0;
    }

    /** Reads the code without echoing it, since it holds the home link's login. */
    private static String readCode() throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] typed = console.readPassword("Paste the pairing code from the dashboard (it won't show): ");
            return typed == null ? "" : new String(typed);
        }
        System.err.println("Paste the pairing code from the dashboard, then press Enter:");
        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return line == null ? "" : line;
    }

    private static int run(Path configFile) throws IOException {
        if (!Files.exists(configFile)) {
            System.err.println("knura-link: " + configFile.toAbsolutePath() + " not found.");
            System.err.println("Add a home link on your account's dashboard, then run: knura-link pair");
            return 1;
        }
        LinkConfig config;
        Knurgn knurgn;
        try {
            config = LinkConfig.load(configFile);
            if (!Files.exists(config.actionsFile())) {
                System.err.println("knura-link: tag rules not found: " + config.actionsFile() + " (see docs/home-link.md)");
                return 1;
            }
            knurgn = KnurgnConfig.load(config.actionsFile(), Clock.systemUTC());
        } catch (IllegalArgumentException e) {
            System.err.println("knura-link: bad config: " + e.getMessage());
            return 1;
        }
        System.out.printf("knura-link: %d knot(s) with actions, from %s%n", knurgn.knotCount(), config.actionsFile());
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            main.interrupt();
            knurgn.close();
        }));
        try {
            new LinkClient(config, new TapRelay(knurgn::onVerifiedTap)).run();
        } catch (LinkClient.RefusedException e) {
            System.err.println("knura-link: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            return 0;
        }
        return 0;
    }
}
