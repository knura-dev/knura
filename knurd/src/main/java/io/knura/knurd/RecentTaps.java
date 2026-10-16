package io.knura.knurd;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last {@link #CAPACITY} taps knurd answered, good or bad, kept in memory for the admin API
 * so you can see what a tag just did without reading logs. Only the result is kept, never the
 * tap URL.
 */
final class RecentTaps {
    static final int CAPACITY = 200;

    /** @param uid null when the tap was too broken to say which tag it came from */
    record Entry(Instant time, String status, String uid, int counter, int actions) {}

    private final Clock clock;
    private final Deque<Entry> entries = new ArrayDeque<>();

    RecentTaps(Clock clock) {
        this.clock = clock;
    }

    synchronized void add(TapResult result, int actions) {
        if (entries.size() == CAPACITY) {
            entries.removeLast();
        }
        entries.addFirst(new Entry(clock.instant(), result.status().jsonName(), result.uid(), result.counter(), actions));
    }

    /** Up to {@code limit} entries, newest first. */
    synchronized List<Entry> latest(int limit) {
        List<Entry> out = new ArrayList<>(Math.min(limit, entries.size()));
        for (Entry e : entries) {
            if (out.size() == limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }
}
