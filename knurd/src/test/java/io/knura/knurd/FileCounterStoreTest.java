package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCounterStoreTest {
    @TempDir
    Path dir;

    @Test
    void countersSurviveRestart() throws Exception {
        Path file = dir.resolve("sub/counters.properties");
        FileCounterStore store = new FileCounterStore(file);
        assertEquals(-1, store.last("04DE5F1EACC040"));
        assertTrue(store.advance("04DE5F1EACC040", 61));
        assertFalse(store.advance("04DE5F1EACC040", 61));
        assertTrue(store.advance("04AAAAAAAAAAAA", 3));

        FileCounterStore reopened = new FileCounterStore(file);
        assertEquals(61, reopened.last("04DE5F1EACC040"));
        assertEquals(3, reopened.last("04AAAAAAAAAAAA"));
        assertFalse(reopened.advance("04DE5F1EACC040", 60));
        assertTrue(reopened.advance("04DE5F1EACC040", 62));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count(), "temp files are cleaned up");
        }
    }

    @Test
    void onlyOneOfManyConcurrentReplaysWins() throws Exception {
        assertOneWinner(new FileCounterStore(dir.resolve("counters.properties")));
        assertOneWinner(new MemoryCounterStore());
    }

    private static void assertOneWinner(CounterStore store) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> same = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                same.add(() -> store.advance("04DE5F1EACC040", 100));
            }
            int wins = 0;
            for (Future<Boolean> f : pool.invokeAll(same)) {
                wins += f.get() ? 1 : 0;
            }
            assertEquals(1, wins);
        } finally {
            pool.shutdown();
        }
    }
}
