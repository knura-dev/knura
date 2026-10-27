package io.knura.link;

import io.knura.core.resolver.FlatJson;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Turns the hosted account's messages into knurgN actions. The account sends one message per tap
 * it has verified and found new:
 *
 * <pre>
 * {"type":"tap","uid":"042D6B02936980","counter":61,"tappedAt":"2026-10-26T22:51:11.123Z"}
 * </pre>
 *
 * What each tag does is decided by the knot rules in the local knurgN config, unless its owner
 * chose actions for it on the dashboard. Then the message also names them, as connector ids:
 *
 * <pre>
 * {"type":"tap","uid":"042D6B02936980","counter":62,"tappedAt":"...","actions":"living-room"}
 * </pre>
 *
 * Those run instead of the knot rule, but only connectors this home link offers to the dashboard
 * ({@code dashboard.connectors} in link.properties): the hosted account can pick among them, never
 * reach anything else. A message seen twice is run once, and knurgN drops taps older than its
 * 30-second expiry, so a link that reconnects late never runs old taps.
 */
final class TapRelay {
    /** knurgN's entry point, {@code Knurgn::onVerifiedTap}. */
    interface Actions {
        int onVerifiedTap(String uid, int counter, Instant tappedAt);
    }

    /** knurgN's entry point for actions chosen on the dashboard. */
    interface Chosen {
        int onVerifiedTap(String uid, int counter, Instant tappedAt, List<String> connectorIds);
    }

    private static final System.Logger LOG = System.getLogger("knura-link");
    private static final Pattern UID = Pattern.compile("[0-9A-F]{14}");
    static final Pattern CONNECTOR = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** NTAG 424 DNA read counters are 24 bits. */
    private static final long MAX_COUNTER = 0xFFFFFF;
    private static final int REMEMBERED = 1000;

    private final Actions actions;
    private final Chosen chosen;
    private final Set<String> offered;
    private final Map<String, Boolean> seen = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    };

    /** Runs only the knot rules; offers the dashboard nothing. */
    TapRelay(Actions actions) {
        this(actions, (uid, counter, at, ids) -> 0, Set.of());
    }

    TapRelay(Actions actions, Chosen chosen, Set<String> offered) {
        this.actions = actions;
        this.chosen = chosen;
        this.offered = new TreeSet<>(offered);
    }

    /** Tells the hosted account which connectors its dashboard may choose; ids only, never secrets. */
    String hello() {
        return "{\"action\":\"hello\",\"connectors\":\"" + String.join(",", offered) + "\"}";
    }

    /** @return the number of actions started, or -1 if the message was ignored */
    synchronized int onMessage(String text) {
        Map<String, Object> message;
        try {
            message = FlatJson.parse(text);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "ignored a message that isn't JSON");
            return -1;
        }
        if (!"tap".equals(message.get("type"))) {
            // Newer servers may send other kinds of messages; skip them quietly.
            LOG.log(Level.DEBUG, "ignored a message of type {0}", message.get("type"));
            return -1;
        }
        if (!(message.get("uid") instanceof String uid) || !UID.matcher(uid).matches()
                || !(message.get("counter") instanceof Long counter) || counter < 0 || counter > MAX_COUNTER
                || !(message.get("tappedAt") instanceof String at)) {
            LOG.log(Level.WARNING, "ignored a tap message with a missing or bad field");
            return -1;
        }
        Instant tappedAt;
        try {
            tappedAt = Instant.parse(at);
        } catch (DateTimeParseException e) {
            LOG.log(Level.WARNING, "ignored a tap message with a bad time");
            return -1;
        }
        String id = uid + ":" + counter;
        if (seen.put(id, Boolean.TRUE) != null) {
            LOG.log(Level.INFO, "tap {0} came twice; ran it once", id);
            return -1;
        }
        if (message.get("actions") instanceof String names && !names.isBlank()) {
            List<String> picked = new ArrayList<>();
            for (String name : names.split(",")) {
                String connector = name.strip();
                if (CONNECTOR.matcher(connector).matches() && offered.contains(connector)) {
                    picked.add(connector);
                } else {
                    // Never runs on the account's say-so alone: only what this home offers.
                    LOG.log(Level.WARNING, "tap {0}: the dashboard chose a connector this home link doesn''t offer; skipped it", id);
                }
            }
            int started = picked.isEmpty() ? 0 : chosen.onVerifiedTap(uid, counter.intValue(), tappedAt, picked);
            LOG.log(Level.INFO, "tap {0} from the hosted account: {1} action(s) chosen on the dashboard started", id, started);
            return started;
        }
        int started = actions.onVerifiedTap(uid, counter.intValue(), tappedAt);
        LOG.log(Level.INFO, "tap {0} from the hosted account: {1} action(s) started", id, started);
        return started;
    }
}
