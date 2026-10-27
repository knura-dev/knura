package io.knura.knurd;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps a flood of bad taps on the public port cheap (threat S7).
 *
 * <ul>
 *   <li>An address that sends more than {@link #MAX_FAILED} taps that fail (malformed, forged or
 *       copied) within {@link #WINDOW} gets 429 for the rest of that window, before any checking.
 *       Addresses on this computer are never blocked: behind Tailscale Funnel or a reverse proxy
 *       every tap comes from there, and blocking it would block real taps too.
 *   <li>The log gets at most {@link #MAX_LOGGED} failed taps per window, then one line saying how
 *       many more there were, so a flood can't fill the disk.
 * </ul>
 *
 * Good taps are always logged and never count against anyone.
 */
final class TapThrottle {
    static final int MAX_FAILED = 30;
    static final int MAX_LOGGED = 20;
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED = 10_000;

    private record Window(Instant start, int failed) {}

    private final Clock clock;
    private final Map<String, Window> byAddress = new HashMap<>();
    private Instant logStart = Instant.EPOCH;
    private int logged;
    private int suppressed;

    TapThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Whether a tap from {@code address} may be checked; false means answer 429. */
    synchronized boolean allows(String address, boolean local) {
        if (local) {
            return true;
        }
        Window w = byAddress.get(address);
        return w == null || !clock.instant().isBefore(w.start().plus(WINDOW)) || w.failed() < MAX_FAILED;
    }

    synchronized void failed(String address, boolean local) {
        if (local) {
            return;
        }
        Instant now = clock.instant();
        if (byAddress.size() >= MAX_TRACKED) {
            byAddress.values().removeIf(w -> !now.isBefore(w.start().plus(WINDOW)));
        }
        Window w = byAddress.get(address);
        if (w == null || !now.isBefore(w.start().plus(WINDOW))) {
            w = new Window(now, 0);
        }
        byAddress.put(address, new Window(w.start(), w.failed() + 1));
    }

    /**
     * Whether to log this failed tap. When a new window starts after some were left out, {@code
     * onSuppressed} gets how many.
     */
    synchronized boolean logFailure(java.util.function.IntConsumer onSuppressed) {
        Instant now = clock.instant();
        if (!now.isBefore(logStart.plus(WINDOW))) {
            if (suppressed > 0) {
                onSuppressed.accept(suppressed);
            }
            logStart = now;
            logged = 0;
            suppressed = 0;
        }
        if (logged < MAX_LOGGED) {
            logged++;
            return true;
        }
        suppressed++;
        return false;
    }
}
