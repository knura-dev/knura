package io.knura.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogsTest {
    @TempDir
    Path dir;

    @Test
    void defaultsToInfoOnStandardErrorOnly() throws IOException {
        Path config = Files.writeString(dir.resolve("knurd.properties"), "listen.port=8424\n");
        Logs.Settings s = Logs.read(config, Map.of());
        assertEquals(Level.INFO, s.level());
        assertNull(s.file());
    }

    @Test
    void readsLevelAndFileFromTheConfig() throws IOException {
        Path config = Files.writeString(dir.resolve("knurd.properties"), "log.level=DEBUG\nlog.file=logs/knurd.log\n");
        Logs.Settings s = Logs.read(config, Map.of());
        assertEquals(Level.FINE, s.level());
        assertEquals(dir.resolve("logs/knurd.log").toAbsolutePath(), s.file());
    }

    @Test
    void environmentOverridesTheConfigLevel() throws IOException {
        Path config = Files.writeString(dir.resolve("knurd.properties"), "log.level=warn\n");
        assertEquals(Level.FINER, Logs.read(config, Map.of(Logs.LEVEL_ENV, "trace")).level());
        assertEquals(Level.WARNING, Logs.read(config, Map.of(Logs.LEVEL_ENV, " ")).level());
    }

    @Test
    void rejectsUnknownLevels() throws IOException {
        Path config = Files.writeString(dir.resolve("knurd.properties"), "log.level=loud\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Logs.read(config, Map.of()));
        assertTrue(e.getMessage().contains("log.level"), e.getMessage());
    }

    @Test
    void formatsOneLineWithCodeLevelNamesAndHidesSecrets() {
        LogRecord r = new LogRecord(Level.FINE, "loaded master.key={0} for {1}");
        r.setParameters(new Object[] {"00112233445566778899AABBCCDDEEFF", "04DE5F1EACC040"});
        r.setLoggerName("knurd");
        String line = new LineFormatter(false).format(r);
        assertEquals("DEBUG knurd: loaded master.key=[hidden] for 04DE5F1EACC040" + System.lineSeparator(), line);
        assertEquals("WARN", LineFormatter.levelName(Level.WARNING));
        assertEquals("ERROR", LineFormatter.levelName(Level.SEVERE));
        assertEquals("TRACE", LineFormatter.levelName(Level.FINEST));
    }

    @Test
    void hidesSecretsInExceptionMessagesToo() {
        LogRecord r = new LogRecord(Level.SEVERE, "request failed");
        r.setThrown(new IllegalStateException("bad token knurd_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"));
        String line = new LineFormatter(true).format(r);
        assertTrue(line.contains("ERROR request failed"), line);
        assertTrue(line.contains("bad token [hidden]"), line);
        assertFalse(line.contains("abcdefghijklmnop"), line);
    }

    @Test
    void fileRollsOverAndKeepsTheSameName() throws IOException {
        Path log = dir.resolve("knurd.log");
        RollingFileHandler h = new RollingFileHandler(log, 200, 2, new LineFormatter(false));
        for (int i = 0; i < 20; i++) {
            h.publish(new LogRecord(Level.INFO, "line number " + i + " with some padding to fill it"));
        }
        h.close();
        assertTrue(Files.size(log) <= 200);
        assertTrue(Files.readString(log, StandardCharsets.UTF_8).contains("line number 19 "));
        assertTrue(Files.exists(dir.resolve("knurd.log.1")));
        assertTrue(Files.exists(dir.resolve("knurd.log.2")));
        assertFalse(Files.exists(dir.resolve("knurd.log.3")));
    }
}
