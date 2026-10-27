package io.knura.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinkConfigTest {
    @TempDir
    Path dir;

    @Test
    void pairingWritesSettingsThatLoadBack() throws Exception {
        Path file = dir.resolve("link.properties");
        LinkConfig.save(file, PairingCode.parse(PairingCodeTest.code("wss://abc.example.com/link", PairingCodeTest.OWNER, PairingCodeTest.TOKEN)));
        Path secrets = dir.resolve("link-secrets.properties");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets)));
        assertTrue(!Files.readString(file).contains("knlink_"), "the token stays out of link.properties");

        LinkConfig config = LinkConfig.load(file);
        assertEquals("wss://abc.example.com/link", config.url().toString());
        assertEquals(PairingCodeTest.OWNER, config.owner());
        assertEquals(PairingCodeTest.TOKEN, config.token());
        assertEquals(dir.resolve("knurgn.properties"), config.actionsFile());
        assertEquals("wss://abc.example.com/link?owner=" + PairingCodeTest.OWNER, LinkClient.address(config).toString());
    }

    @Test
    void aBareHostGetsAPathBeforeTheOwner() {
        LinkConfig config = new LinkConfig(java.net.URI.create("wss://link.example.com"), PairingCodeTest.OWNER,
                PairingCodeTest.TOKEN, dir.resolve("knurgn.properties"));
        assertEquals("wss://link.example.com/?owner=" + PairingCodeTest.OWNER, LinkClient.address(config).toString());
    }

    @Test
    void readsWhichConnectorsTheDashboardMayChoose() throws Exception {
        Path file = dir.resolve("link.properties");
        LinkConfig.save(file, PairingCode.parse(PairingCodeTest.code("wss://abc.example.com/link", PairingCodeTest.OWNER, PairingCodeTest.TOKEN)));
        assertEquals(null, LinkConfig.load(file).dashboardConnectors(), "all of them unless it says otherwise");
        String paired = Files.readString(file);
        Files.writeString(file, paired + "dashboard.connectors=living-room, porch\n");
        assertEquals(java.util.Set.of("living-room", "porch"), LinkConfig.load(file).dashboardConnectors());
        Files.writeString(file, paired + "dashboard.connectors=none\n");
        assertEquals(java.util.Set.of(), LinkConfig.load(file).dashboardConnectors());
        Files.writeString(file, paired + "dashboard.connectors=living room\n");
        assertThrows(IllegalArgumentException.class, () -> LinkConfig.load(file));
    }

    @Test
    void wontPairOverAnExistingPairing() throws Exception {
        Path file = dir.resolve("link.properties");
        PairingCode code = PairingCode.parse(PairingCodeTest.code("wss://abc.example.com/link", PairingCodeTest.OWNER, PairingCodeTest.TOKEN));
        LinkConfig.save(file, code);
        assertThrows(IllegalArgumentException.class, () -> LinkConfig.save(file, code));
    }

    @Test
    void refusesASecretsFileOthersCanRead() throws Exception {
        Path file = dir.resolve("link.properties");
        LinkConfig.save(file, PairingCode.parse(PairingCodeTest.code("wss://abc.example.com/link", PairingCodeTest.OWNER, PairingCodeTest.TOKEN)));
        Files.setPosixFilePermissions(dir.resolve("link-secrets.properties"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalArgumentException.class, () -> LinkConfig.load(file));
    }

    @Test
    void refusesUnknownSettings() throws Exception {
        Path file = dir.resolve("link.properties");
        LinkConfig.save(file, PairingCode.parse(PairingCodeTest.code("wss://abc.example.com/link", PairingCodeTest.OWNER, PairingCodeTest.TOKEN)));
        Files.writeString(file, "urll=wss://x.example.com/link\n", java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> LinkConfig.load(file));
    }
}
