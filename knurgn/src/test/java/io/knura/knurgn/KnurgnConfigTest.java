package io.knura.knurgn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnurgnConfigTest {
    static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    @TempDir
    Path dir;

    @Test
    void loadsExampleConfig() throws Exception {
        Path file = dir.resolve("knurgn.properties");
        Files.copy(Path.of("knurgn.example.properties"), file);
        writeSecrets("desk-webhook=" + SECRET, "rw-------");
        try (Knurgn knurgn = KnurgnConfig.load(file, Clock.systemUTC())) {
            assertEquals(1, knurgn.knotCount());
        }
    }

    @Test
    void refusesSecretsOthersCanRead() throws Exception {
        Path file = dir.resolve("knurgn.properties");
        Files.copy(Path.of("knurgn.example.properties"), file);
        writeSecrets("desk-webhook=" + SECRET, "rw-r--r--");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KnurgnConfig.load(file, Clock.systemUTC()));
        assertTrue(e.getMessage().contains("chmod 600"), e.getMessage());
    }

    @Test
    void buildsKnots() {
        Properties rules = rules();
        rules.setProperty("knot.04aabbccddeeff.actions", "a, b");
        rules.setProperty("webhook.b.url", "https://example.com/b");
        Properties secrets = secrets();
        secrets.setProperty("b", SECRET);
        List<Knot> knots = KnurgnConfig.knots(rules, secrets, "s", Clock.systemUTC());
        assertEquals(2, knots.size());
        Knot unnamed = knots.stream().filter(k -> k.uid().equals("04AABBCCDDEEFF")).findFirst().orElseThrow();
        assertEquals("04AABBCCDDEEFF", unnamed.name());
        assertEquals(List.of("a", "b"), unnamed.actions().stream().map(Connector::id).toList());
    }

    @Test
    void buildsMatterConnector() {
        Properties rules = rules();
        rules.setProperty("knot.042D6B02936980.actions", "a,home");
        rules.setProperty("matter.home.url", "http://127.0.0.1:5541/tap");
        Properties secrets = secrets();
        secrets.setProperty("home", SECRET);
        Knot knot = KnurgnConfig.knots(rules, secrets, "s", Clock.systemUTC()).get(0);
        assertEquals(List.of("webhook", "matter"), knot.actions().stream().map(Connector::type).toList());
    }

    @Test
    void buildsHomeAssistantConnector() {
        Properties rules = rules();
        rules.setProperty("knot.042D6B02936980.actions", "a,living-room");
        rules.setProperty("homeassistant.living-room.url", "http://192.168.56.101:8123");
        Properties secrets = secrets();
        secrets.setProperty("living-room", HomeAssistantConnectorTest.HOOK);
        Knot knot = KnurgnConfig.knots(rules, secrets, "s", Clock.systemUTC()).get(0);
        assertEquals(List.of("webhook", "homeassistant"), knot.actions().stream().map(Connector::type).toList());
    }

    @Test
    void rejectsMistakes() {
        assertBad("homeassistant.lr.url", "http://192.168.56.101:8123", "no secret for homeassistant lr");
        assertBad("homeassistant.a.url", "http://192.168.56.101:8123", "two connectors are named a");
        assertBad("matter.a.url", "http://127.0.0.1:5541/tap", "two connectors are named a");
        assertBad("matter.home.url", "http://127.0.0.1:5541/tap", "no secret for matter home");
        assertBad("knot.042D6B02936980.actions", "nope", "no connector named");
        assertBad("knot.042D6B0293698.actions", "a", "14 hex digits");
        assertBad("knot.042D6B02936980.name", "desk candle", "letters, digits");
        assertBad("knot.042D6B02936980.tier", "sensitive", "unknown setting");
        assertBad("webhook.a.secret", SECRET, "secrets don't belong");
        assertBad("webhook.b.url", "https://example.com/b", "no secret for webhook b");
        assertBad("webhook.a.url", "http://example.com/hook", "must use https://");
        assertBad("knot.04AABBCCDDEEFF.name", "desk", "missing");
        assertBad("knot.042D6B02936980.actions", "a,a", "twice");

        Properties bareSecret = secrets();
        bareSecret.setProperty(HomeAssistantConnectorTest.HOOK, "");
        IllegalArgumentException bare = assertThrows(
                IllegalArgumentException.class, () -> KnurgnConfig.knots(rules(), bareSecret, "s", Clock.systemUTC()));
        assertTrue(bare.getMessage().contains("connector's id in front"), bare.getMessage());
        assertFalse(bare.getMessage().contains(HomeAssistantConnectorTest.HOOK), bare.getMessage());

        Properties extraSecret = secrets();
        extraSecret.setProperty("ghost", SECRET);
        assertThrows(IllegalArgumentException.class, () -> KnurgnConfig.knots(rules(), extraSecret, "s", Clock.systemUTC()));
    }

    private static Properties rules() {
        Properties p = new Properties();
        p.setProperty("knot.042D6B02936980.name", "desk");
        p.setProperty("knot.042D6B02936980.actions", "a");
        p.setProperty("webhook.a.url", "http://127.0.0.1:8425/hook");
        return p;
    }

    private static Properties secrets() {
        Properties p = new Properties();
        p.setProperty("a", SECRET);
        return p;
    }

    private static void assertBad(String key, String value, String message) {
        Properties rules = rules();
        rules.setProperty(key, value);
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> KnurgnConfig.knots(rules, secrets(), "s", Clock.systemUTC()));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private void writeSecrets(String line, String perms) throws Exception {
        Path secrets = dir.resolve("knurgn-secrets.properties");
        Files.writeString(secrets, line + "\n");
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString(perms));
    }
}
