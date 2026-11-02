package io.knura.knurd;

import io.knura.knurgn.Delivery;
import io.knura.knurgn.Knurgn;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The tap listener knurd hands to its tap server: it passes verified taps to the current knurgN
 * engine, which can be swapped for a new one when the actions file changes, so changing what a
 * tag does never needs a restart. Taps already being delivered by the old engine finish first.
 */
final class LiveActions implements KnurdServer.TapListener, AutoCloseable {
    private final AtomicReference<Knurgn> current;
    private final Clock clock;
    private volatile Function<String, String> labels = uid -> null;
    private final Map<String, Last> last = new ConcurrentHashMap<>();

    /**
     * How a connector's latest action ended: {@code outcome} is {@code delivered}, {@code refused}
     * or {@code expired}, and {@code test} says whether it was a test. Kept in memory only.
     */
    record Last(Instant time, String outcome, String detail, boolean test) {}

    /** @param initial the engine from the actions file, or null when there is none yet */
    LiveActions(Knurgn initial, Clock clock) {
        this.current = new AtomicReference<>(initial);
        this.clock = clock;
        watch(initial);
    }

    private void watch(Knurgn k) {
        if (k != null) {
            k.watch(r -> last.put(r.connectorId(), new Last(clock.instant(), r.outcome().name().toLowerCase(java.util.Locale.ROOT),
                    r.detail(), r.event().counter() == 0)));
        }
    }

    /** How each connector's latest action ended, by connector id. */
    Map<String, Last> last() {
        return Map.copyOf(last);
    }

    /**
     * Sends one test event to connector {@code id} and waits for its answer, or null when there is
     * no such connector. {@code uid} names the tag the event is for.
     */
    Delivery tryConnector(String id, String uid) {
        Knurgn k = current.get();
        return k == null ? null : k.tryConnector(id, uid, labels.apply(uid));
    }

    /** Where to find the name the owner gave each tag, sent along with each tap. */
    void labels(Function<String, String> labels) {
        this.labels = labels;
    }

    @Override
    public int onVerifiedTap(String uid, int counter) {
        Knurgn k = current.get();
        return k == null ? 0 : k.onVerifiedTap(uid, counter, clock.instant(), labels.apply(uid));
    }

    /**
     * Runs a tag's actions as if it had just been tapped, for the setup page's "Send a test". The
     * event is a normal one with counter 0, which no real tap ever has (counters start at 1).
     */
    int test(String uid) {
        Knurgn k = current.get();
        return k == null ? 0 : k.onVerifiedTap(uid, 0, clock.instant(), labels.apply(uid));
    }

    int knotCount() {
        Knurgn k = current.get();
        return k == null ? 0 : k.knotCount();
    }

    /** Starts using {@code next}; the old engine is closed in the background. */
    void replace(Knurgn next) {
        watch(next);
        Knurgn old = current.getAndSet(next);
        if (old != null) {
            Thread.ofVirtual().name("knurgn-retire").start(old::close);
        }
    }

    @Override
    public void close() {
        Knurgn k = current.getAndSet(null);
        if (k != null) {
            k.close();
        }
    }
}
