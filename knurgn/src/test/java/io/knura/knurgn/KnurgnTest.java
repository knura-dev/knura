package io.knura.knurgn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KnurgnTest {
    static final String UID = "042D6B02936980";

    /** Answers each attempt from a script, then keeps repeating the last answer. */
    static class Scripted implements Connector {
        final Queue<Delivery> script;
        final BlockingQueue<TapEvent> seen = new LinkedBlockingQueue<>();
        Delivery last;

        Scripted(Delivery... answers) {
            script = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public String type() {
            return "test";
        }

        @Override
        public synchronized Delivery deliver(TapEvent event, Duration timeout) {
            seen.add(event);
            if (!script.isEmpty()) {
                last = script.poll();
            }
            return last;
        }
    }

    private final BlockingQueue<Knurgn.Report> reports = new LinkedBlockingQueue<>();

    private Knurgn engine(Connector connector, Duration expiry) {
        Knot knot = new Knot(UID, "desk-candle", List.of(connector));
        return new Knurgn(List.of(knot), Clock.systemUTC(), expiry, Duration.ofMillis(20), reports::add);
    }

    @Test
    void retriesUntilDelivered() throws Exception {
        Scripted c = new Scripted(Delivery.failed("HTTP 503"), Delivery.failed("HTTP 503"), Delivery.delivered("HTTP 200"));
        try (Knurgn knurgn = engine(c, Duration.ofSeconds(5))) {
            assertEquals(1, knurgn.onVerifiedTap(UID, 61));
            Knurgn.Report r = reports.poll(5, TimeUnit.SECONDS);
            assertEquals(Knurgn.Outcome.DELIVERED, r.outcome());
            assertEquals(3, r.attempts());
            assertEquals(UID + ":61", r.event().id());
            assertEquals("desk-candle", r.event().knot());
            // Every retry carries the same event, so receivers can drop duplicates by id.
            assertEquals(3, c.seen.stream().map(TapEvent::id).filter((UID + ":61")::equals).count());
        }
    }

    @Test
    void refusalIsNotRetried() throws Exception {
        Scripted c = new Scripted(Delivery.refused("HTTP 401"));
        try (Knurgn knurgn = engine(c, Duration.ofSeconds(5))) {
            knurgn.onVerifiedTap(UID, 1);
            Knurgn.Report r = reports.poll(5, TimeUnit.SECONDS);
            assertEquals(Knurgn.Outcome.REFUSED, r.outcome());
            assertEquals(1, r.attempts());
            assertEquals(1, c.seen.size());
        }
    }

    @Test
    void expiresInsteadOfRunningLate() throws Exception {
        Scripted c = new Scripted(Delivery.failed("ConnectException"));
        try (Knurgn knurgn = engine(c, Duration.ofMillis(300))) {
            knurgn.onVerifiedTap(UID, 1);
            Knurgn.Report r = reports.poll(5, TimeUnit.SECONDS);
            assertEquals(Knurgn.Outcome.EXPIRED, r.outcome());
            int attempts = c.seen.size();
            Thread.sleep(300);
            assertEquals(attempts, c.seen.size(), "no attempts after expiry");
        }
    }

    @Test
    void crashingConnectorIsReportedNotRetried() throws Exception {
        Connector boom = new Scripted() {
            @Override
            public synchronized Delivery deliver(TapEvent event, Duration timeout) {
                throw new IllegalStateException("bug");
            }
        };
        try (Knurgn knurgn = engine(boom, Duration.ofSeconds(5))) {
            knurgn.onVerifiedTap(UID, 1);
            assertEquals(Knurgn.Outcome.REFUSED, reports.poll(5, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void tagWithoutKnotDoesNothing() throws Exception {
        Scripted c = new Scripted(Delivery.delivered("HTTP 200"));
        try (Knurgn knurgn = engine(c, Duration.ofSeconds(5))) {
            assertEquals(0, knurgn.onVerifiedTap("04000000000001", 5));
            assertNull(reports.poll(100, TimeUnit.MILLISECONDS));
            assertEquals(0, c.seen.size());
        }
    }

    @Test
    void tapVerifiedElsewhereKeepsItsTimeAndExpiry() throws Exception {
        Scripted c = new Scripted(Delivery.delivered("HTTP 200"));
        Instant tappedAt = Instant.now().minusMillis(500);
        try (Knurgn knurgn = engine(c, Duration.ofSeconds(5))) {
            knurgn.onVerifiedTap(UID, 7, tappedAt);
            assertEquals(Knurgn.Outcome.DELIVERED, reports.poll(5, TimeUnit.SECONDS).outcome());
            assertEquals(tappedAt, c.seen.poll().tappedAt());
        }
    }

    @Test
    void tapVerifiedTooLongAgoRunsNothing() throws Exception {
        Scripted c = new Scripted(Delivery.delivered("HTTP 200"));
        try (Knurgn knurgn = engine(c, Duration.ofSeconds(5))) {
            knurgn.onVerifiedTap(UID, 8, Instant.now().minusSeconds(6));
            assertEquals(Knurgn.Outcome.EXPIRED, reports.poll(5, TimeUnit.SECONDS).outcome());
            assertEquals(0, c.seen.size());
        }
    }

    /** A second connector, with its own id. */
    static Scripted named(String id, Delivery... answers) {
        return new Scripted(answers) {
            @Override
            public String id() {
                return id;
            }
        };
    }

    @Test
    void chosenActionsRunInsteadOfTheKnotRule() throws Exception {
        Scripted rule = new Scripted(Delivery.delivered("HTTP 200"));
        Scripted porch = named("porch", Delivery.delivered("HTTP 200"));
        Knot knot = new Knot(UID, "desk-candle", List.of(rule));
        try (Knurgn knurgn = new Knurgn(List.of(knot), List.of(porch), Clock.systemUTC(), Duration.ofSeconds(5),
                Duration.ofMillis(20), reports::add)) {
            assertEquals(List.of("porch", "scripted"), List.copyOf(knurgn.connectorIds()));
            assertEquals(1, knurgn.onVerifiedTap(UID, 9, Instant.now(), List.of("porch", "gone", "porch")));
            Knurgn.Report r = reports.poll(5, TimeUnit.SECONDS);
            assertEquals("porch", r.connectorId());
            assertEquals("desk-candle", r.event().knot());
            assertEquals(0, rule.seen.size());
            assertNull(reports.poll(200, TimeUnit.MILLISECONDS), "runs once, and never the rule");
        }
    }

    @Test
    void chosenActionsWorkForATagWithNoKnotRule() throws Exception {
        Scripted porch = named("porch", Delivery.delivered("HTTP 200"));
        try (Knurgn knurgn = new Knurgn(List.of(), List.of(porch), Clock.systemUTC(), Duration.ofSeconds(5),
                Duration.ofMillis(20), reports::add)) {
            assertEquals(0, knurgn.onVerifiedTap("04406B02936980", 3));
            assertEquals(1, knurgn.onVerifiedTap("04406B02936980", 4, Instant.now(), List.of("porch")));
            assertEquals("04406B02936980", reports.poll(5, TimeUnit.SECONDS).event().knot());
        }
    }

    @Test
    void dashboardNamesRideAlongButNeverRenameTheKnot() throws Exception {
        Scripted rule = new Scripted(Delivery.delivered("HTTP 200"));
        Scripted porch = named("porch", Delivery.delivered("HTTP 200"));
        Knot knot = new Knot(UID, "desk-candle", List.of(rule));
        try (Knurgn knurgn = new Knurgn(List.of(knot), List.of(porch), Clock.systemUTC(), Duration.ofSeconds(5),
                Duration.ofMillis(20), reports::add)) {
            assertEquals(1, knurgn.onVerifiedTap(UID, 10, Instant.now(), "Desk candle"));
            TapEvent ruled = reports.poll(5, TimeUnit.SECONDS).event();
            assertEquals("desk-candle", ruled.knot());
            assertEquals("Desk candle", ruled.name());

            assertEquals(1, knurgn.onVerifiedTap("04406B02936980", 4, Instant.now(), List.of("porch"), "Front door"));
            TapEvent chosen = reports.poll(5, TimeUnit.SECONDS).event();
            assertEquals("04406B02936980", chosen.knot());
            assertEquals("Front door", chosen.name());
        }
    }
}
