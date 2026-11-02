package io.knura.knurgn;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * knurgN, Knura's actions engine. knurd hands it each verified, first-time tap; knurgN looks up
 * that tag's {@link Knot} by UID and delivers the tap to each of the knot's connectors.
 *
 * <p>Deliveries run in the background, so the phone gets its answer straight away. A failed
 * attempt is retried with growing gaps (1 s, 2 s, 4 s...), and an action that still hasn't gone
 * through 30 seconds after the tap is dropped rather than run late. Every action ends as one
 * {@link Report}: delivered, refused or expired.
 *
 * <p>There is deliberately no way to run an action except through {@link #onVerifiedTap}, and
 * nothing from the tap URL reaches this class except the verified UID and counter.
 */
public final class Knurgn implements AutoCloseable {
    public static final Duration DEFAULT_EXPIRY = Duration.ofSeconds(30);
    public static final Duration DEFAULT_FIRST_RETRY = Duration.ofSeconds(1);
    /** Longest a single attempt may take. */
    static final Duration MAX_ATTEMPT = Duration.ofSeconds(10);

    private static final System.Logger LOG = System.getLogger("knurgn");

    /** How one action for one tap ended. */
    public record Report(TapEvent event, String connectorId, Outcome outcome, int attempts, String detail) {}

    public enum Outcome {
        DELIVERED,
        REFUSED,
        EXPIRED;

        String logName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<String, Knot> knots = new HashMap<>();
    /** Every connector in the config, by id, whether or not a knot rule uses it. */
    private final TreeMap<String, Connector> connectors = new TreeMap<>();
    private final Clock clock;
    private final Duration expiry;
    private final Duration firstRetry;
    private final Consumer<Report> reports;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Consumer<Report> watcher = r -> {};

    public Knurgn(List<Knot> knots, Clock clock) {
        this(knots, List.of(), clock);
    }

    /** {@code connectors} may include ones no knot uses, for {@link #onVerifiedTap(String, int, Instant, List)}. */
    public Knurgn(List<Knot> knots, List<Connector> connectors, Clock clock) {
        this(knots, connectors, clock, DEFAULT_EXPIRY, DEFAULT_FIRST_RETRY, Knurgn::log);
    }

    Knurgn(List<Knot> knots, Clock clock, Duration expiry, Duration firstRetry, Consumer<Report> reports) {
        this(knots, List.of(), clock, expiry, firstRetry, reports);
    }

    Knurgn(List<Knot> knots, List<Connector> connectors, Clock clock, Duration expiry, Duration firstRetry,
            Consumer<Report> reports) {
        for (Knot knot : knots) {
            if (this.knots.put(knot.uid(), knot) != null) {
                throw new IllegalArgumentException("knot " + knot.uid() + " is defined twice");
            }
            knot.actions().forEach(c -> this.connectors.put(c.id(), c));
        }
        connectors.forEach(c -> this.connectors.put(c.id(), c));
        this.clock = clock;
        this.expiry = expiry;
        this.firstRetry = firstRetry;
        this.reports = reports;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "knurgn-retry");
            t.setDaemon(true);
            return t;
        });
    }

    public int knotCount() {
        return knots.size();
    }

    /**
     * Also tells {@code watcher} how each action ended, besides logging it, so knurd's dashboard
     * can show whether each connector is answering. It runs on the delivery thread, so keep it quick.
     */
    public void watch(Consumer<Report> watcher) {
        this.watcher = watcher == null ? r -> {} : watcher;
    }

    /**
     * Sends one test event to the connector {@code id} now, once, without retrying, and waits for
     * its answer: the dashboard's "Send a test" for one connector. The event has counter 0, which
     * no real tap has. Returns null when there is no such connector.
     */
    public Delivery tryConnector(String id, String uid, String name) {
        Connector connector = connectors.get(id);
        if (connector == null) {
            return null;
        }
        TapEvent event = TapEvent.of(uid, 0, uid, clock.instant(), name);
        Delivery delivery;
        try {
            delivery = connector.deliver(event, MAX_ATTEMPT);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "connector " + id + " failed unexpectedly", e);
            delivery = Delivery.refused("connector error");
        }
        Outcome outcome = switch (delivery.status()) {
            case DELIVERED -> Outcome.DELIVERED;
            case REFUSED -> Outcome.REFUSED;
            case FAILED -> Outcome.EXPIRED;
        };
        report(event, connector, outcome, 1, delivery.detail());
        return delivery;
    }

    /** The ids of every connector in the config, sorted. */
    public Set<String> connectorIds() {
        return Collections.unmodifiableSet(connectors.navigableKeySet());
    }

    /**
     * Starts the actions for a tap that knurd has verified and recorded. Call this only for taps
     * whose MAC checked out and whose counter was new. Returns straight away.
     *
     * @param uid the tag UID, uppercase hex
     * @return the number of actions started (0 if the tag has no knot rule)
     */
    public int onVerifiedTap(String uid, int counter) {
        return onVerifiedTap(uid, counter, clock.instant());
    }

    /**
     * Like {@link #onVerifiedTap(String, int)}, for a tap that was verified elsewhere at {@code
     * tappedAt}, such as by a hosted Knura account that passes it on through the home link. The
     * 30-second expiry counts from {@code tappedAt}, so a tap that arrives late runs no action.
     */
    public int onVerifiedTap(String uid, int counter, Instant tappedAt) {
        return onVerifiedTap(uid, counter, tappedAt, (String) null);
    }

    /**
     * Like {@link #onVerifiedTap(String, int, Instant)}, and each event also carries {@code name},
     * the name the owner gave the tag on a hosted Knura account's dashboard (null for none). It is
     * a label only: the knot rule still decides what runs, and {@code knot} keeps its config name.
     */
    public int onVerifiedTap(String uid, int counter, Instant tappedAt, String name) {
        Knot knot = knots.get(uid);
        if (knot == null) {
            LOG.log(Level.INFO, "tap {0}:{1}: no knot rule for this tag, nothing to do", uid, Integer.toString(counter));
            return 0;
        }
        TapEvent event = TapEvent.of(uid, counter, knot.name(), tappedAt, name);
        Instant deadline = tappedAt.plus(expiry);
        for (Connector connector : knot.actions()) {
            submit(event, connector, 1, deadline, firstRetry);
        }
        return knot.actions().size();
    }

    /**
     * Like {@link #onVerifiedTap(String, int, Instant)}, but runs the connectors named in {@code
     * connectorIds} instead of the tag's knot rule: the actions its owner chose for it elsewhere,
     * such as on a hosted Knura account's dashboard. Ids that aren't in the config are skipped.
     */
    public int onVerifiedTap(String uid, int counter, Instant tappedAt, List<String> connectorIds) {
        return onVerifiedTap(uid, counter, tappedAt, connectorIds, null);
    }

    /**
     * Like {@link #onVerifiedTap(String, int, Instant, List)}, and each event also carries the
     * tag's dashboard {@code name} (null for none), as in {@link #onVerifiedTap(String, int,
     * Instant, String)}.
     */
    public int onVerifiedTap(String uid, int counter, Instant tappedAt, List<String> connectorIds, String name) {
        Knot knot = knots.get(uid);
        TapEvent event = TapEvent.of(uid, counter, knot == null ? uid : knot.name(), tappedAt, name);
        Instant deadline = tappedAt.plus(expiry);
        int started = 0;
        for (String id : new LinkedHashSet<>(connectorIds)) {
            Connector connector = connectors.get(id);
            if (connector == null) {
                LOG.log(Level.WARNING, "tap {0}:{1}: no connector named {2} here, skipped it", uid, Integer.toString(counter), id);
                continue;
            }
            submit(event, connector, 1, deadline, firstRetry);
            started++;
        }
        return started;
    }

    private void submit(TapEvent event, Connector connector, int attempt, Instant deadline, Duration nextGap) {
        try {
            workers.execute(() -> attempt(event, connector, attempt, deadline, nextGap));
        } catch (RejectedExecutionException e) {
            report(event, connector, Outcome.EXPIRED, attempt - 1, "knurd is shutting down");
        }
    }

    private void attempt(TapEvent event, Connector connector, int attempt, Instant deadline, Duration nextGap) {
        Duration left = Duration.between(clock.instant(), deadline);
        if (left.isNegative() || left.isZero()) {
            report(event, connector, Outcome.EXPIRED, attempt - 1, "no time left");
            return;
        }
        Delivery delivery;
        long started = System.nanoTime();
        try {
            delivery = connector.deliver(event, left.compareTo(MAX_ATTEMPT) < 0 ? left : MAX_ATTEMPT);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "connector " + connector.id() + " failed unexpectedly", e);
            delivery = Delivery.refused("connector error");
        }
        LOG.log(Level.DEBUG, "tap {0} action={1} ({2}) attempt {3}: {4} ({5}) in {6} ms",
                event.id(), connector.id(), connector.type(), attempt, delivery.status(), delivery.detail(),
                Long.toString((System.nanoTime() - started) / 1_000_000));
        switch (delivery.status()) {
            case DELIVERED -> report(event, connector, Outcome.DELIVERED, attempt, delivery.detail());
            case REFUSED -> report(event, connector, Outcome.REFUSED, attempt, delivery.detail());
            case FAILED -> {
                if (clock.instant().plus(nextGap).isBefore(deadline)) {
                    try {
                        scheduler.schedule(
                                () -> submit(event, connector, attempt + 1, deadline, nextGap.multipliedBy(2)),
                                nextGap.toMillis(),
                                TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException e) {
                        report(event, connector, Outcome.EXPIRED, attempt, "knurd is shutting down");
                    }
                } else {
                    report(event, connector, Outcome.EXPIRED, attempt, delivery.detail());
                }
            }
        }
    }

    private void report(TapEvent event, Connector connector, Outcome outcome, int attempts, String detail) {
        Report r = new Report(event, connector.id(), outcome, attempts, detail);
        reports.accept(r);
        try {
            watcher.accept(r);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "could not record how action " + connector.id() + " ended", e);
        }
    }

    private static void log(Report r) {
        LOG.log(
                r.outcome() == Outcome.DELIVERED ? Level.INFO : Level.WARNING,
                "tap {0} knot={1} action={2}: {3} after {4} attempt(s) ({5})",
                r.event().id(),
                r.event().knot(),
                r.connectorId(),
                r.outcome().logName(),
                r.attempts(),
                r.detail());
    }

    /** Stops retries and waits briefly for deliveries already in flight. */
    @Override
    public void close() {
        scheduler.shutdownNow();
        workers.shutdown();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
