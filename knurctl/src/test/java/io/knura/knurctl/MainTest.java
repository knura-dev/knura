package io.knura.knurctl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
import io.knura.core.provision.TagKeySet;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void printsUsageWithoutCommand() {
        assertEquals(2, run());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("provision"));
    }

    @Test
    void rejectsUnknownCommand() {
        assertEquals(2, run("frobnicate"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown command"));
    }

    @Test
    void initWritesARandomMasterKeyOnce() throws Exception {
        Path config = dir.resolve("knurd.properties");
        assertEquals(0, run("init", "--config", config.toString()));

        Properties p = new Properties();
        p.load(Files.newBufferedReader(config));
        assertEquals(16, Hex.decode(p.getProperty("master.key")).length);
        assertEquals("8424", p.getProperty("listen.port"));

        String before = Files.readString(config);
        assertEquals(2, run("init", "--config", config.toString()));
        assertEquals(before, Files.readString(config), "init never overwrites a master key");
    }

    @Test
    void provisionNeedsAMasterKey() {
        assertEquals(2, run("provision", "--config", dir.resolve("missing.properties").toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("knurctl init"));
    }

    @Test
    void keyFileFillsMissingSlotsWithFactoryKeys() throws Exception {
        Properties p = new Properties();
        p.setProperty("key0", "000102030405060708090a0b0c0d0e0f");
        p.setProperty("key2", "202122232425262728292A2B2C2D2E2F");
        TagKeySet keys = KeyFile.parse(p, "test");
        assertArrayEquals(Hex.decode("000102030405060708090A0B0C0D0E0F"), keys.key(0));
        assertArrayEquals(new byte[16], keys.key(1));
        assertArrayEquals(Hex.decode("202122232425262728292A2B2C2D2E2F"), keys.key(2));
    }

    @Test
    void keyFileRejectsBadKeys() {
        Properties p = new Properties();
        p.setProperty("key1", "1234");
        assertThrows(Main.UsageException.class, () -> KeyFile.parse(p, "test"));
    }
}
