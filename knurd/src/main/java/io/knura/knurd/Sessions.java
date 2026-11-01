package io.knura.knurd;

import io.knura.core.admin.AdminTokens;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Browser sign-ins to the setup page, kept in memory only: restarting knurd signs everyone out.
 * A session ends after {@link #IDLE} without use or {@link #MAX} after signing in, whichever
 * comes first. Only a hash of each session id is kept, as with tokens.
 */
final class Sessions {
    static final String COOKIE = "knura_session";
    static final Duration IDLE = Duration.ofHours(12);
    static final Duration MAX = Duration.ofDays(7);
    static final int LIMIT = 50;

    private record Session(Instant started, Instant lastUsed) {}

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> byHash = new HashMap<>();

    Sessions(Clock clock) {
        this.clock = clock;
    }

    /** Starts a session and returns its id, for the cookie. */
    synchronized String start() {
        Instant now = clock.instant();
        expire(now);
        if (byHash.size() >= LIMIT) {
            // Drop the least recently used, so signing in always works.
            byHash.entrySet().stream().min(Map.Entry.comparingByValue(
                    (a, b) -> a.lastUsed().compareTo(b.lastUsed())))
                    .map(Map.Entry::getKey).ifPresent(byHash::remove);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = "knurs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byHash.put(AdminTokens.hash(id), new Session(now, now));
        return id;
    }

    /** Whether {@code id} is a live session; using it keeps it alive. */
    synchronized boolean check(String id) {
        if (id == null || !id.startsWith("knurs_")) {
            return false;
        }
        Instant now = clock.instant();
        expire(now);
        String hash = AdminTokens.hash(id);
        Session s = byHash.get(hash);
        if (s == null) {
            return false;
        }
        byHash.put(hash, new Session(s.started(), now));
        return true;
    }

    synchronized void end(String id) {
        if (id != null) {
            byHash.remove(AdminTokens.hash(id));
        }
    }

    /** Signs everyone out, as after a password change. */
    synchronized void endAll() {
        byHash.clear();
    }

    synchronized int count() {
        expire(clock.instant());
        return byHash.size();
    }

    private void expire(Instant now) {
        byHash.values().removeIf(s -> !now.isBefore(s.lastUsed().plus(IDLE)) || !now.isBefore(s.started().plus(MAX)));
    }

    /** The session id from a Cookie header, or null. */
    static String fromCookie(String header) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) {
                return p.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }
}
