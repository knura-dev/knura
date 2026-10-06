package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnurdConfigTest {
    @TempDir
    Path dir;

    @Test
    void loadsExampleConfig() throws Exception {
        Path file = dir.resolve("knurd.properties");
        Files.copy(Path.of("knurd.example.properties"), file);
        KnurdConfig config = KnurdConfig.load(file);
        assertEquals("127.0.0.1", config.host());
        assertEquals(8424, config.port());
        assertEquals(dir.resolve("knurd-counters.properties").toAbsolutePath(), config.countersFile());
        assertTrue(config.keys().metaReadKey().isPresent());
        assertTrue(config.keys().fileReadKey("04DE5F1EACC040").isPresent());
    }

    @Test
    void perTagKeyUidIsCaseInsensitive() {
        Properties p = new Properties();
        p.setProperty("sdm.fileReadKey.04de5f1eacc040", "0102030405060708090a0b0c0d0e0f10");
        KnurdConfig config = KnurdConfig.fromProperties(p, dir);
        assertEquals(1, config.keys().fileReadKey("04DE5F1EACC040").orElseThrow()[0]);
        assertTrue(config.keys().fileReadKey("04000000000001").isEmpty());
        assertTrue(config.keys().metaReadKey().isEmpty());
    }

    @Test
    void rejectsBadValues() {
        Properties noKeys = new Properties();
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(noKeys, dir));

        Properties shortKey = new Properties();
        shortKey.setProperty("sdm.fileReadKey", "0011");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(shortKey, dir));

        Properties badPort = new Properties();
        badPort.setProperty("sdm.fileReadKey", "00000000000000000000000000000000");
        badPort.setProperty("listen.port", "http");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(badPort, dir));
    }
}
