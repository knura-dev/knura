package io.knura.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TapRelayTest {
    record Call(String uid, int counter, Instant tappedAt, String name) {
        Call(String uid, int counter, Instant tappedAt) {
            this(uid, counter, tappedAt, null);
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private final TapRelay relay = new TapRelay((uid, counter, at, name) -> {
        calls.add(new Call(uid, counter, at, name));
        return 1;
    });

    @Test
    void runsEachTapOnce() {
        String tap = "{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"counter\":61,\"tappedAt\":\"2026-10-26T22:51:11.123Z\"}";
        assertEquals(1, relay.onMessage(tap));
        assertEquals(-1, relay.onMessage(tap));
        assertEquals(List.of(new Call("042D6B02936980", 61, Instant.parse("2026-10-26T22:51:11.123Z"))), calls);
    }

    @Test
    void ignoresAnythingElse() {
        assertEquals(-1, relay.onMessage("not json"));
        assertEquals(-1, relay.onMessage("{\"type\":\"hello\"}"));
        assertEquals(-1, relay.onMessage("{\"type\":\"tap\",\"uid\":\"042d6b02936980\",\"counter\":1,\"tappedAt\":\"2026-10-26T22:51:11Z\"}"));
        assertEquals(-1, relay.onMessage("{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"counter\":16777216,\"tappedAt\":\"2026-10-26T22:51:11Z\"}"));
        assertEquals(-1, relay.onMessage("{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"counter\":\"1\",\"tappedAt\":\"2026-10-26T22:51:11Z\"}"));
        assertEquals(-1, relay.onMessage("{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"counter\":1,\"tappedAt\":\"yesterday\"}"));
        assertEquals(List.of(), calls);
    }

    @Test
    void runsActionsChosenOnTheDashboardButOnlyOfferedOnes() {
        List<String> chosen = new ArrayList<>();
        TapRelay offering = new TapRelay((uid, counter, at, name) -> {
            calls.add(new Call(uid, counter, at, name));
            return 1;
        }, (uid, counter, at, ids, name) -> {
            chosen.addAll(ids);
            return ids.size();
        }, java.util.Set.of("living-room", "porch"));
        assertEquals("{\"action\":\"hello\",\"connectors\":\"living-room,porch\"}", offering.hello());

        String base = "{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"tappedAt\":\"2026-10-26T22:51:11Z\",";
        assertEquals(1, offering.onMessage(base + "\"counter\":1,\"actions\":\"porch, front-door\"}"));
        assertEquals(List.of("porch"), chosen);
        assertEquals(List.of(), calls, "the knot rule doesn't run as well");

        assertEquals(0, offering.onMessage(base + "\"counter\":2,\"actions\":\"front-door\"}"));
        assertEquals(List.of("porch"), chosen, "never a connector this home doesn't offer");

        assertEquals(1, offering.onMessage(base + "\"counter\":3,\"actions\":\"\"}"));
        assertEquals(1, calls.size(), "no choice means the knot rule");
    }

    @Test
    void offersNothingByDefault() {
        assertEquals("{\"action\":\"hello\",\"connectors\":\"\"}", relay.hello());
        assertEquals(0, relay.onMessage(
                "{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"counter\":4,\"tappedAt\":\"2026-10-26T22:51:11Z\",\"actions\":\"porch\"}"));
        assertEquals(List.of(), calls);
    }

    @Test
    void passesTheDashboardNameOnAsASafeLabel() {
        List<String> names = new ArrayList<>();
        TapRelay offering = new TapRelay((uid, counter, at, name) -> {
            calls.add(new Call(uid, counter, at, name));
            return 1;
        }, (uid, counter, at, ids, name) -> {
            names.add(name);
            return ids.size();
        }, java.util.Set.of("porch"));
        String base = "{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"tappedAt\":\"2026-10-26T22:51:11Z\",";

        assertEquals(1, offering.onMessage(base + "\"counter\":1,\"name\":\"Front door\"}"));
        assertEquals("Front door", calls.get(0).name());
        assertEquals(1, offering.onMessage(base + "\"counter\":2,\"name\":\"Porch \\u2600\",\"actions\":\"porch\"}"));
        assertEquals(List.of("Porch \u2600"), names);
        assertEquals(1, offering.onMessage(base + "\"counter\":3,\"name\":\"  \"}"));
        assertEquals(null, calls.get(1).name(), "a blank name is no name");
    }

    @Test
    void cleansNames() {
        assertEquals("Frontdoor", TapRelay.label("Front\ndoor\u0000\u202e"));
        assertEquals("x".repeat(40), TapRelay.label("x".repeat(60)));
        assertEquals("\uD83D\uDEAA".repeat(40), TapRelay.label("\uD83D\uDEAA".repeat(41)), "never splits a character");
        assertEquals(null, TapRelay.label("\t"));
    }
}
