package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
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
        assertNull(config.actionsFile());
    }

    @Test
    void actionsFileIsRelativeToConfig() {
        Properties p = new Properties();
        p.setProperty("master.key", "00112233445566778899AABBCCDDEEFF");
        p.setProperty("actions.file", "knurgn.properties");
        assertEquals(dir.resolve("knurgn.properties"), KnurdConfig.fromProperties(p, dir).actionsFile());
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

    @Test
    void masterKeyDerivesPerTagKeys() {
        Properties p = new Properties();
        p.setProperty("master.key", "00112233445566778899AABBCCDDEEFF");
        p.setProperty("sdm.fileReadKey.04000000000001", "0102030405060708090a0b0c0d0e0f10");
        TagKeys keys = KnurdConfig.fromProperties(p, dir).keys();
        assertArrayEquals(Hex.decode("D23924B4018F2BC86493E513E09B9692"), keys.metaReadKey().orElseThrow());
        assertArrayEquals(
                Hex.decode("D3D118FEC036256A73FE0BBE36FFF021"), keys.fileReadKey("04DE5F1EACC040").orElseThrow());
        assertEquals(1, keys.fileReadKey("04000000000001").orElseThrow()[0]);
    }

    @Test
    void masterKeyCannotBeMixedWithSharedKeys() {
        Properties withMeta = new Properties();
        withMeta.setProperty("master.key", "00112233445566778899AABBCCDDEEFF");
        withMeta.setProperty("sdm.metaReadKey", "00000000000000000000000000000000");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(withMeta, dir));

        Properties withShared = new Properties();
        withShared.setProperty("master.key", "00112233445566778899AABBCCDDEEFF");
        withShared.setProperty("sdm.fileReadKey", "00000000000000000000000000000000");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(withShared, dir));

        Properties badMaster = new Properties();
        badMaster.setProperty("master.key", "0011");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(badMaster, dir));
    }
}
