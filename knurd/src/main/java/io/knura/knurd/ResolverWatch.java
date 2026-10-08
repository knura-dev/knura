package io.knura.knurd;

import io.knura.core.resolver.OwnerRecord;
import io.knura.core.resolver.ResolverCheck;
import io.knura.core.resolver.ResolverClient;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Checks now and then that the tap resolver still sends this owner's taps here, under a record
 * signed with the owner's key. If someone moved them (with a stolen owner key, or by changing the
 * resolver's stored record without it), knurd says so loudly instead of simply seeing fewer taps.
 *
 * <p>This can't catch a resolver that shows knurd the right record and sends phones elsewhere;
 * nothing outside the resolver can. Owners who don't want to trust a resolver provision tags
 * with their own address ({@code knurctl provision --base-url}).
 */
final class ResolverWatch implements AutoCloseable {
    static final Duration EVERY = Duration.ofHours(6);
    private static final System.Logger LOG = System.getLogger("knurd");

    /** Reads the owner's record; {@link ResolverClient#fetch} in real use. */
    @FunctionalInterface
    interface Fetcher {
        Optional<OwnerRecord> fetch(String owner) throws IOException;
    }

    private final KnurdConfig.Resolver resolver;
    private final String publicUrl;
    private final Fetcher fetcher;
    private ScheduledExecutorService timer;
    private ResolverCheck.Status last;

    ResolverWatch(KnurdConfig.Resolver resolver, String publicUrl, Fetcher fetcher) {
        this.resolver = resolver;
        this.publicUrl = publicUrl;
        this.fetcher = fetcher;
    }

    /** Checks once now, then every {@link #EVERY}. */
    void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "knurd-resolver-watch");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::checkNow, 0, EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /** Checks once and logs the outcome. Returns null if the resolver could not be reached. */
    synchronized ResolverCheck.Result checkNow() {
        ResolverCheck.Result result;
        try {
            result = ResolverCheck.check(
                    fetcher.fetch(resolver.owner()), resolver.owner(), resolver.publicKey(), publicUrl);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "could not check where " + resolver.url() + " sends taps: " + e.getMessage());
            return null;
        }
        if (result.ok()) {
            if (last != ResolverCheck.Status.OK) {
                LOG.log(Level.INFO, resolver.url() + ": " + result.message());
            }
        } else {
            LOG.log(Level.WARNING, resolver.url() + ": " + result.message() + ". " + advice(result.status()));
        }
        last = result.status();
        return result;
    }

    private static String advice(ResolverCheck.Status status) {
        return switch (status) {
            case NOT_REGISTERED -> "Finish setting it up with: knurctl resolver register";
            case OTHER_SERVER -> "TAPS ARE NOT REACHING THIS KNURD. If this knurd's address changed, run"
                    + " 'knurctl resolver register' here. If you didn't move it, your resolver.key may be"
                    + " stolen: see knura-resolver/README.md.";
            case OTHER_KEY, BAD_SIGNATURE -> "TAPS MAY NOT BE REACHING THIS KNURD, and the change was not made"
                    + " with your resolver.key. See knura-resolver/README.md.";
            case OK -> "";
        };
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }
}
