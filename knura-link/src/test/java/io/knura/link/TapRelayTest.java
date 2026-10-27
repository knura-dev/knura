package io.knura.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TapRelayTest {
    record Call(String uid, int counter, Instant tappedAt) {}

    private final List<Call> calls = new ArrayList<>();
    private final TapRelay relay = new TapRelay((uid, counter, at) -> {
        calls.add(new Call(uid, counter, at));
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
        TapRelay offering = new TapRelay((uid, counter, at) -> {
            calls.add(new Call(uid, counter, at));
            return 1;
        }, (uid, counter, at, ids) -> {
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
}
