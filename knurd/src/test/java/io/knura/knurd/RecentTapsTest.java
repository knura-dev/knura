package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The dashboard's tap history: kept in a file, read back after a restart, and counted per day. */
class RecentTapsTest {
    private static final String A = "04DE5F1EACC040";
    private static final String B = "04356B02936980";

    @TempDir
    Path dir;

    private final AdminServerTest.MutableClock clock = new AdminServerTest.MutableClock(Instant.parse("2026-11-02T10:00:00Z"));

    private static TapResult ok(String uid, int counter) {
        return new TapResult(TapResult.Status.OK, uid, counter, null);
    }

    @Test
    void historySurvivesARestartAndStaysPrivate() throws Exception {
        Path file = dir.resolve(RecentTaps.FILE);
        RecentTaps taps = new RecentTaps(clock, file);
        taps.add(ok(A, 1), 2);
        clock.advance(Duration.ofMinutes(1));
        taps.add(TapResult.failed(TapResult.Status.MALFORMED, "no c"), 0);
        clock.advance(Duration.ofMinutes(1));
        taps.add(new TapResult(TapResult.Status.REPLAY, A, 1, "old counter"), 0);

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        RecentTaps again = new RecentTaps(clock, file);
        List<RecentTaps.Entry> latest = again.latest(10);
        assertEquals(3, latest.size());
        assertEquals("replay", latest.get(0).status());
        assertNull(latest.get(1).uid());
        assertEquals(new RecentTaps.Entry(Instant.parse("2026-11-02T10:00:00Z"), "ok", A, 1, 2), latest.get(2));
        assertEquals(1, again.latest(10, e -> !e.stopped()).size());
    }

    @Test
    void oldAndDamagedLinesAreDropped() throws Exception {
        Path file = dir.resolve(RecentTaps.FILE);
        Files.writeString(file, "2025-10-01T00:00:00Z\tok\t" + A + "\t1\t0\n" // more than a year old
                + "not a tap\n"
                + "2026-11-01T00:00:00Z\tok\tnot-a-uid\t1\t0\n"
                + "2026-11-01T09:00:00Z\tok\t" + A + "\t2\t1\n", StandardCharsets.UTF_8);
        RecentTaps taps = new RecentTaps(clock, file);
        assertEquals(1, taps.latest(10).size());
        assertEquals(1, Files.readAllLines(file).size(), "the file is tidied up too");
    }

    @Test
    void aFileThatCantBeReadStartsEmpty() throws Exception {
        Path file = dir.resolve("folder-not-file");
        Files.createDirectory(file);
        RecentTaps taps = new RecentTaps(clock, file);
        taps.add(ok(A, 1), 0); // can't be saved either; only logged
        assertEquals(1, taps.latest(10).size());
    }

    @Test
    void withoutAFileOnlyTheLatestAreKept() {
        RecentTaps taps = new RecentTaps(clock);
        for (int i = 1; i <= RecentTaps.CAPACITY + 5; i++) {
            taps.add(ok(A, i), 0);
        }
        assertEquals(RecentTaps.CAPACITY, taps.latest(RecentTaps.MAX_LIMIT).size());
        assertEquals(RecentTaps.CAPACITY + 5, taps.latest(1).get(0).counter());
    }

    @Test
    void tapsAreCountedPerDay() {
        RecentTaps taps = new RecentTaps(clock);
        clock.advance(Duration.ofDays(-3));
        taps.add(ok(A, 1), 0); // too old for 3 days
        clock.advance(Duration.ofDays(1));
        taps.add(ok(A, 2), 0);
        taps.add(ok(B, 1), 0);
        clock.advance(Duration.ofDays(2));
        taps.add(ok(A, 3), 0);
        taps.add(new TapResult(TapResult.Status.REJECTED, null, -1, "bad MAC"), 0);

        RecentTaps.Days d = taps.days(3, ZoneOffset.UTC);
        assertEquals(List.of(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")), d.days());
        assertArrayEquals(new int[] {2, 0, 1}, d.ok());
        assertArrayEquals(new int[] {0, 0, 1}, d.stopped());
        assertArrayEquals(new int[] {1, 0, 1}, d.tags().get(A));
        assertArrayEquals(new int[] {1, 0, 0}, d.tags().get(B));
        assertFalse(d.tags().containsKey(null));
    }

    @Test
    void linesReadBackAsWritten() {
        RecentTaps.Entry e = new RecentTaps.Entry(Instant.parse("2026-11-02T10:00:00Z"), "replay", A, 7, 0);
        assertEquals(e, RecentTaps.parse(RecentTaps.format(e).strip()));
        assertNull(RecentTaps.parse("2026-11-02T10:00:00Z\tOK\t" + A + "\t7\t0"));
        assertTrue(RecentTaps.format(new RecentTaps.Entry(e.time(), "malformed", null, -1, 0)).contains("\t-\t"));
    }
}
