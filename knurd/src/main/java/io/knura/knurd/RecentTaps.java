package io.knura.knurd;

import io.knura.core.admin.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The taps knurd answered, good or bad, for the admin API and the dashboard, so you can see what
 * a tag did without reading logs. Only the result is kept, never the tap URL.
 *
 * <p>With a file (the dashboard's tap history, {@value #FILE} next to knurd.properties) the taps
 * are also written there, one line each, and read back at start-up, so the history and its charts
 * survive a restart. Taps older than {@link #KEEP} are dropped, and at most {@link #MAX_KEPT} are
 * kept. Without a file only the last {@link #CAPACITY} are kept, in memory.
 *
 * <p>A line is {@code time<TAB>status<TAB>uid<TAB>counter<TAB>actions}, with {@code -} for an
 * unknown tag. Lines that don't read that way are skipped, so a damaged file loses only them.
 */
final class RecentTaps {
    private static final System.Logger LOG = System.getLogger("knurd.taps");
    /** How many taps are kept without a file. */
    static final int CAPACITY = 200;
    /** The most taps one request may ask for. */
    static final int MAX_LIMIT = 1000;
    static final String FILE = "knurd-taps.log";
    static final Duration KEEP = Duration.ofDays(365);
    static final int MAX_KEPT = 100_000;
    /** The most days {@link #days} counts. */
    static final int MAX_DAYS = 90;
    private static final int COMPACT_EVERY = 2000;
    private static final Pattern UID = Pattern.compile("[0-9A-F]{14}");

    /** @param uid null when the tap was too broken to say which tag it came from */
    record Entry(Instant time, String status, String uid, int counter, int actions) {
        /** Whether this tap was turned away: copied, forged or broken. */
        boolean stopped() {
            return !"ok".equals(status);
        }
    }

    /** Taps per day, oldest day first: in all, turned away, and the good taps of each tag. */
    record Days(List<LocalDate> days, int[] ok, int[] stopped, Map<String, int[]> tags) {}

    private final Clock clock;
    private final Path file;
    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    /** Lines in the file now; more than {@code entries} once taps have been dropped from memory. */
    private int fileLines;
    private int appended;
    private boolean warned;

    RecentTaps(Clock clock) {
        this.clock = clock;
        this.file = null;
        this.capacity = CAPACITY;
    }

    /**
     * Keeps the tap history in {@code file}, reading what is there already. A file that can't be
     * read is logged and history starts empty; one that can't be written only loses new lines.
     */
    RecentTaps(Clock clock, Path file) {
        this.clock = clock;
        this.file = file;
        this.capacity = MAX_KEPT;
        try {
            load();
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.WARNING, "could not read the tap history " + file + "; starting a new one", e);
        }
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Instant oldest = clock.instant().minus(KEEP);
        int skipped = 0;
        int lines = 0;
        List<Entry> read = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lines++;
            Entry e = parse(line);
            if (e == null || e.time().isBefore(oldest)) {
                skipped++;
            } else {
                read.add(e);
            }
        }
        read.sort((a, b) -> b.time().compareTo(a.time()));
        for (Entry e : read) {
            if (entries.size() == capacity) {
                skipped++;
                continue;
            }
            entries.addLast(e);
        }
        fileLines = lines;
        if (skipped > 0) {
            rewrite();
        }
    }

    static Entry parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 5) {
            return null;
        }
        try {
            String uid = "-".equals(f[2]) ? null : f[2];
            if (uid != null && !UID.matcher(uid).matches() || !f[1].matches("[a-z]{1,20}")) {
                return null;
            }
            return new Entry(Instant.parse(f[0]), f[1], uid, Integer.parseInt(f[3]), Integer.parseInt(f[4]));
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    static String format(Entry e) {
        return e.time() + "\t" + e.status() + "\t" + (e.uid() == null ? "-" : e.uid()) + "\t" + e.counter() + "\t"
                + e.actions() + "\n";
    }

    synchronized void add(TapResult result, int actions) {
        Entry e = new Entry(clock.instant(), result.status().jsonName(), result.uid(), result.counter(), actions);
        if (entries.size() == capacity) {
            entries.removeLast();
        }
        entries.addFirst(e);
        if (file == null) {
            return;
        }
        try {
            if (!Files.exists(file)) {
                PrivateFiles.create(file, "");
                fileLines = 0;
            }
            Files.writeString(file, format(e), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            fileLines++;
            if (++appended % COMPACT_EVERY == 0) {
                dropOld();
                if (fileLines > entries.size()) {
                    rewrite();
                }
            }
            warned = false;
        } catch (IOException | RuntimeException ex) {
            if (!warned) {
                LOG.log(Level.WARNING, "could not save a tap to the tap history " + file
                        + ": " + ex.getMessage() + "; the dashboard will lose it at the next restart");
                warned = true;
            }
        }
    }

    /** Drops taps past {@link #KEEP} from memory. */
    private void dropOld() {
        Instant oldest = clock.instant().minus(KEEP);
        while (!entries.isEmpty() && entries.peekLast().time().isBefore(oldest)) {
            entries.removeLast();
        }
    }

    private void rewrite() throws IOException {
        StringBuilder out = new StringBuilder();
        for (Iterator<Entry> it = entries.descendingIterator(); it.hasNext(); ) {
            out.append(format(it.next()));
        }
        if (Files.exists(file)) {
            PrivateFiles.replace(file, out.toString());
        } else {
            PrivateFiles.create(file, out.toString());
        }
        fileLines = entries.size();
    }

    /** Up to {@code limit} entries, newest first. */
    synchronized List<Entry> latest(int limit) {
        return latest(limit, e -> true);
    }

    /** Up to {@code limit} entries that pass {@code keep}, newest first. */
    synchronized List<Entry> latest(int limit, Predicate<Entry> keep) {
        List<Entry> out = new ArrayList<>(Math.min(limit, 64));
        for (Entry e : entries) {
            if (out.size() == limit) {
                break;
            }
            if (keep.test(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** How many taps came in on each of the last {@code count} days, today last, in {@code zone}. */
    synchronized Days days(int count, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate first = today.minusDays(count - 1L);
        List<LocalDate> days = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            days.add(first.plusDays(i));
        }
        int[] ok = new int[count];
        int[] stopped = new int[count];
        Map<String, int[]> tags = new LinkedHashMap<>();
        for (Entry e : entries) {
            LocalDate day = LocalDate.ofInstant(e.time(), zone);
            if (day.isBefore(first)) {
                break; // newest first, so the rest are older still
            }
            int i = (int) ChronoUnit.DAYS.between(first, day);
            if (i >= count) {
                continue; // a clock that went back
            }
            if (e.stopped()) {
                stopped[i]++;
            } else {
                ok[i]++;
                if (e.uid() != null) {
                    tags.computeIfAbsent(e.uid(), u -> new int[count])[i]++;
                }
            }
        }
        return new Days(days, ok, stopped, tags);
    }
}
