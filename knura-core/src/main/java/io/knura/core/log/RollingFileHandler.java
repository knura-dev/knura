package io.knura.core.log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Writes log lines to one file, always under the same name so {@code tail -F} follows it. When
 * the file passes {@code limitBytes} it becomes {@code <file>.1}, the older ones move up to
 * {@code <file>.2} and so on, and the oldest past {@code keep} is deleted.
 */
final class RollingFileHandler extends Handler {
    private final Path file;
    private final long limitBytes;
    private final int keep;
    private OutputStream out;
    private long written;

    RollingFileHandler(Path file, long limitBytes, int keep, Formatter formatter) throws IOException {
        this.file = file;
        this.limitBytes = limitBytes;
        this.keep = keep;
        setFormatter(formatter);
        open();
    }

    private void open() throws IOException {
        out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        written = Files.size(file);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (out == null || !isLoggable(record)) {
            return;
        }
        byte[] line;
        try {
            line = getFormatter().format(record).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            reportError(null, e, ErrorManager.FORMAT_FAILURE);
            return;
        }
        try {
            if (written > 0 && written + line.length > limitBytes) {
                roll();
            }
            out.write(line);
            out.flush();
            written += line.length;
        } catch (IOException e) {
            reportError(null, e, ErrorManager.WRITE_FAILURE);
        }
    }

    private void roll() throws IOException {
        out.close();
        Files.deleteIfExists(numbered(keep));
        for (int i = keep - 1; i >= 1; i--) {
            if (Files.exists(numbered(i))) {
                Files.move(numbered(i), numbered(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (keep >= 1) {
            Files.move(file, numbered(1), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.delete(file);
        }
        open();
    }

    private Path numbered(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }

    @Override
    public synchronized void flush() {
        try {
            if (out != null) {
                out.flush();
            }
        } catch (IOException e) {
            reportError(null, e, ErrorManager.FLUSH_FAILURE);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            reportError(null, e, ErrorManager.CLOSE_FAILURE);
        }
        out = null;
    }
}
