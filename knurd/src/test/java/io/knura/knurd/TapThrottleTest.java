package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TapThrottleTest {
    private Instant now = Instant.parse("2026-10-27T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    };
    private final TapThrottle throttle = new TapThrottle(clock);

    @Test
    void blocksAnAddressThatKeepsSendingBadTapsForTheRestOfTheMinute() {
        for (int i = 0; i < TapThrottle.MAX_FAILED; i++) {
            assertTrue(throttle.allows("203.0.113.9", false));
            throttle.failed("203.0.113.9", false);
        }
        assertFalse(throttle.allows("203.0.113.9", false));
        assertTrue(throttle.allows("198.51.100.4", false), "only that address");
        now = now.plus(TapThrottle.WINDOW);
        assertTrue(throttle.allows("203.0.113.9", false));
    }

    @Test
    void neverBlocksThisComputerBecauseATunnelSendsEveryTapFromIt() {
        for (int i = 0; i < TapThrottle.MAX_FAILED * 3; i++) {
            throttle.failed("127.0.0.1", true);
        }
        assertTrue(throttle.allows("127.0.0.1", true));
    }

    @Test
    void logsOnlySomeFailedTapsAMinuteThenSaysHowManyWereLeftOut() {
        List<Integer> summaries = new ArrayList<>();
        int logged = 0;
        for (int i = 0; i < 50; i++) {
            if (throttle.logFailure(summaries::add)) {
                logged++;
            }
        }
        assertEquals(TapThrottle.MAX_LOGGED, logged);
        now = now.plus(Duration.ofMinutes(1));
        assertTrue(throttle.logFailure(summaries::add));
        assertEquals(List.of(50 - TapThrottle.MAX_LOGGED), summaries);
    }
}
