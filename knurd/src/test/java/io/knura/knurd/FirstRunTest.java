package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.PrivateFiles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the installers' {@code knurd --home <folder> [--lan]} makes, and the setup code for a Knura with no screen. */
class FirstRunTest {
    @TempDir
    Path dir;

    @Test
    void makesAStarterFolderOnceOwnerOnly() throws Exception {
        Path home = dir.resolve("knura");
        Path config = FirstRun.prepare(home, false);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)));
        KnurdConfig c = KnurdConfig.load(config);
        assertEquals("master", c.keys().mode());
        assertEquals("127.0.0.1", c.admin().host());
        assertEquals(0, c.admin().lanPort());
        assertTrue(Files.exists(home.resolve(FirstRun.TOKENS_FILE)));
        String first = Files.readString(config);
        FirstRun.prepare(home, true);
        assertEquals(first, Files.readString(config), "never touches existing settings, or their master key");
        assertFalse(Files.exists(home.resolve("knurd-admin.p12")));
    }

    @Test
    void lanTurnsOnTheWifiListenerWithItsCertificate() throws Exception {
        Path config = FirstRun.prepare(dir.resolve("pi"), true);
        KnurdConfig.Admin a = KnurdConfig.load(config).admin();
        assertEquals(KnurdConfig.DEFAULT_LAN_PORT, a.lanPort());
        assertFalse(a.tls(), "loopback stays plain HTTP");
        assertTrue(Files.exists(dir.resolve("pi/knurd-admin.p12")));
    }

    @Test
    void macLaunchAgentIsValidXml() throws Exception {
        String plist = Desktop.plist(Path.of("/Applications/Knura.app/Contents/MacOS/knurd"),
                Path.of("/Users/sam/Library/Application Support/Knura & Co"));
        var doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(plist.replaceFirst("<!DOCTYPE[^>]*>", "").getBytes()));
        String text = doc.getDocumentElement().getTextContent();
        assertTrue(text.contains("io.knura.knurd"));
        assertTrue(text.contains("/Users/sam/Library/Application Support/Knura & Co"));
        assertTrue(text.contains("--home"));
    }

    @Test
    void setupCodeIsOneTimeAndForgiving() throws Exception {
        SetupCode code = new SetupCode(dir.resolve(SetupCode.FILE));
        assertFalse(code.waiting());
        String c = code.ensure();
        assertTrue(c.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"), c);
        assertEquals(c, code.ensure(), "same code until it's used");
        assertTrue(code.matches(c.toLowerCase().replace("-", " ")));
        assertFalse(code.matches("AAAA-BBBB-CCCC"));
        code.used();
        assertFalse(code.waiting());
        assertFalse(code.matches(c));
    }

    @Test
    void firstPasswordFromAnotherDeviceNeedsTheSetupCode() throws Exception {
        SecureRandom random = new SecureRandom();
        Path knurd = dir.resolve("knurd.properties");
        PrivateFiles.create(knurd, "master.key=00112233445566778899AABBCCDDEEFF\n");
        Path tokens = dir.resolve("tokens.properties");
        PrivateFiles.create(tokens, AdminTokens.format(Map.of()));
        AdminServerTest.MutableClock clock = new AdminServerTest.MutableClock(Instant.parse("2026-11-01T00:00:00Z"));
        AuditLog audit = new AuditLog(dir.resolve("audit.log"), clock);
        LiveActions live = new LiveActions(null, Clock.systemUTC());
        AdminServer admin = new AdminServer("127.0.0.1", 0, null, new TokenStore(tokens), audit, new MemoryCounterStore(),
                new RecentTaps(clock), new AdminServer.Info("master", null, null, 0, clock.instant()), clock, null);
        SetupCode code = new SetupCode(dir.resolve(SetupCode.FILE));
        admin.manage(new AdminServer.Manage(new AdminPassword(dir.resolve(AdminPassword.FILE), random, 1000),
                new Sessions(clock), new HomeSettings(knurd, null, live, Clock.systemUTC()), new Tailscale(null, 0), null,
                code));
        admin.thisComputer = a -> false; // as if from a laptop on the same Wi-Fi
        admin.start();
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        try {
            assertEquals("{\"passwordSet\":false,\"signedIn\":false,\"needsSetupCode\":true,\"setupCodeWaiting\":false}",
                    send(http, admin, "GET", "/v1/session", null).body());
            assertEquals(403, send(http, admin, "POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}").statusCode(),
                    "no code made: only the Knura computer may set it");
            String c = code.ensure();
            assertTrue(send(http, admin, "GET", "/v1/session", null).body().contains("\"setupCodeWaiting\":true"));
            assertEquals(401, send(http, admin, "POST", "/v1/password",
                    "{\"password\":\"lantern-orbit-42\",\"setupCode\":\"AAAA-BBBB-CCCC\"}").statusCode());
            HttpResponse<String> ok = send(http, admin, "POST", "/v1/password",
                    "{\"password\":\"lantern-orbit-42\",\"setupCode\":\"" + c + "\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertFalse(code.waiting(), "used up");
            assertFalse(Files.readString(dir.resolve("audit.log")).contains(c), "the code isn't logged by the API");
        } finally {
            admin.close();
            audit.close();
            live.close();
        }
    }

    private static HttpResponse<String> send(HttpClient http, AdminServer admin, String method, String path, String json)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin.port() + path))
                .header(AdminServer.REQUEST_HEADER, "1")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
