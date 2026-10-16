package io.knura.knurd;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps counters in memory only. Restarting knurd forgets them, so use it for tests and trials. */
public final class MemoryCounterStore implements CounterStore {
    private final Map<String, Integer> counters = new ConcurrentHashMap<>();

    @Override
    public boolean advance(String uidHex, int counter) {
        boolean[] fresh = {false};
        counters.compute(uidHex, (uid, last) -> {
            if (last == null || counter > last) {
                fresh[0] = true;
                return counter;
            }
            return last;
        });
        return fresh[0];
    }

    @Override
    public int last(String uidHex) {
        return counters.getOrDefault(uidHex, -1);
    }

    @Override
    public SortedMap<String, Integer> snapshot() {
        return new TreeMap<>(counters);
    }
}
