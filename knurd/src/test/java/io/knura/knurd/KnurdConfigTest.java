package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
    void publicUrlMustBeHttpsUnlessLocal() {
        assertNull(KnurdConfig.publicUrl(""));
        assertEquals("https://desk.tail1234.ts.net", KnurdConfig.publicUrl("https://desk.tail1234.ts.net/"));
        assertEquals("https://example.org/knura", KnurdConfig.publicUrl("https://example.org/knura"));
        assertEquals("http://localhost:8424", KnurdConfig.publicUrl("http://localhost:8424"));
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.publicUrl("http://desk.tail1234.ts.net"));
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.publicUrl("desk.tail1234.ts.net"));
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.publicUrl("https://example.org/?a=1"));
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.publicUrl("ftp://example.org"));
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

    @Test
    void masterKeyFromFile() throws Exception {
        Path keyFile = dir.resolve("master.key");
        Files.writeString(keyFile, "00112233445566778899AABBCCDDEEFF\n");
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        Properties p = new Properties();
        p.setProperty("master.key.file", "master.key");
        TagKeys keys = KnurdConfig.fromProperties(p, dir).keys();
        assertArrayEquals(Hex.decode("D23924B4018F2BC86493E513E09B9692"), keys.metaReadKey().orElseThrow());

        p.setProperty("master.key", "00112233445566778899AABBCCDDEEFF");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(p, dir));
    }

    @Test
    void rejectsBadMasterKeyFiles() throws Exception {
        Properties p = new Properties();
        p.setProperty("master.key.file", "master.key");
        IllegalArgumentException missing =
                assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(p, dir));
        assertTrue(missing.getMessage().contains("not found"), missing.getMessage());

        Path keyFile = dir.resolve("master.key");
        Files.writeString(keyFile, "00112233445566778899AABBCCDDEEFF");
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r-----"));
        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(p, dir));
        assertTrue(shared.getMessage().contains("chmod 600"), shared.getMessage());

        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(keyFile, "secret-but-not-hex");
        IllegalArgumentException bad =
                assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(p, dir));
        assertFalse(bad.getMessage().contains("secret-but-not-hex"), "the key must not be echoed");
    }

    private static Properties withResolver() {
        Properties p = new Properties();
        p.setProperty("master.key", "00112233445566778899AABBCCDDEEFF");
        p.setProperty("public.url", "https://fedora.tail113e80.ts.net");
        p.setProperty("resolver.owner", "zk7hq4m2xa");
        p.setProperty("resolver.publicKey", "D75A980182B10AB7D54BFED3C964073A0EE172F3DAA62325AF021A68F707511A");
        p.setProperty("resolver.key", "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        return p;
    }

    @Test
    void resolverSettings() {
        Properties plain = withResolver();
        plain.remove("resolver.owner");
        assertNull(KnurdConfig.fromProperties(plain, dir).resolver());

        KnurdConfig.Resolver resolver = KnurdConfig.fromProperties(withResolver(), dir).resolver();
        assertEquals(new KnurdConfig.Resolver(
                "https://tap.knura.io",
                "zk7hq4m2xa",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), resolver);

        Properties local = withResolver();
        local.setProperty("resolver.url", "http://127.0.0.1:8425/");
        assertEquals("http://127.0.0.1:8425", KnurdConfig.fromProperties(local, dir).resolver().url());
    }

    @Test
    void rejectsBadResolverSettings() {
        Properties noPublicUrl = withResolver();
        noPublicUrl.remove("public.url");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(noPublicUrl, dir));

        Properties badOwner = withResolver();
        badOwner.setProperty("resolver.owner", "../admin");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(badOwner, dir));

        Properties plainHttp = withResolver();
        plainHttp.setProperty("resolver.url", "http://tap.knura.io");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(plainHttp, dir));

        Properties mismatched = withResolver();
        mismatched.setProperty("resolver.publicKey", "00".repeat(32));
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(mismatched, dir));

        Properties noKey = withResolver();
        noKey.remove("resolver.publicKey");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(noKey, dir));
    }
}
