package io.knura.knurd;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Slows down token guessing: after {@link #MAX_FAILURES} bad tokens from one address within
 * {@link #WINDOW}, that address's bad tokens get 429 Too Many Requests for {@link #LOCKOUT}.
 * Tokens are far too long to guess anyway; this slows a misbehaving device and makes an attack
 * obvious in the audit log. A good token is never locked out, so nobody can lock you out.
 */
final class LoginThrottle {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(5);
    static final Duration LOCKOUT = Duration.ofMinutes(5);
    private static final int MAX_TRACKED = 10_000;

    private record Failures(Instant first, int count, Instant lockedUntil) {}

    private final Clock clock;
    private final Map<String, Failures> byAddress = new HashMap<>();

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** How long the address must still wait, or {@link Duration#ZERO} if it may try. */
    synchronized Duration waitFor(String address) {
        Failures f = byAddress.get(address);
        Instant now = clock.instant();
        if (f == null || f.lockedUntil() == null || !now.isBefore(f.lockedUntil())) {
            return Duration.ZERO;
        }
        return Duration.between(now, f.lockedUntil());
    }

    synchronized void failed(String address) {
        Instant now = clock.instant();
        if (byAddress.size() >= MAX_TRACKED) {
            byAddress.values().removeIf(f -> f.first().plus(WINDOW).isBefore(now)
                    && (f.lockedUntil() == null || f.lockedUntil().isBefore(now)));
        }
        Failures f = byAddress.get(address);
        if (f == null || !now.isBefore(f.first().plus(WINDOW))) {
            f = new Failures(now, 0, null);
        }
        int count = f.count() + 1;
        byAddress.put(address, new Failures(f.first(), count, count >= MAX_FAILURES ? now.plus(LOCKOUT) : null));
    }

    synchronized void succeeded(String address) {
        byAddress.remove(address);
    }
}
