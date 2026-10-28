package io.knura.core.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * One line per message, in the level names the code uses ({@code DEBUG}, {@code INFO},
 * {@code WARN}, {@code ERROR}), with secrets hidden by {@link Redact}:
 *
 * <pre>
 * 2026-10-28 22:30:01.123 INFO  knurd: tap ok uid=04356B02936980 counter=12 (8 ms)
 * </pre>
 *
 * Without the time when {@code withTime} is false, for the systemd journal, which adds its own.
 */
public final class LineFormatter extends Formatter {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final boolean withTime;

    public LineFormatter(boolean withTime) {
        this.withTime = withTime;
    }

    @Override
    public String format(LogRecord record) {
        StringBuilder line = new StringBuilder(160);
        if (withTime) {
            line.append(TIME.format(Instant.ofEpochMilli(record.getMillis()))).append(' ');
        }
        String level = levelName(record.getLevel());
        line.append(level).append(" ".repeat(Math.max(1, 6 - level.length())));
        if (record.getLoggerName() != null && !record.getLoggerName().isEmpty()) {
            line.append(record.getLoggerName()).append(": ");
        }
        line.append(formatMessage(record));
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            line.append(System.lineSeparator()).append(trace.toString().stripTrailing());
        }
        return Redact.scrub(line.toString()) + System.lineSeparator();
    }

    /** System.Logger's level names, which java.util.logging maps to its own. */
    static String levelName(Level level) {
        int v = level.intValue();
        if (v >= Level.SEVERE.intValue()) {
            return "ERROR";
        } else if (v >= Level.WARNING.intValue()) {
            return "WARN";
        } else if (v >= Level.INFO.intValue()) {
            return "INFO";
        } else if (v >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }
}
