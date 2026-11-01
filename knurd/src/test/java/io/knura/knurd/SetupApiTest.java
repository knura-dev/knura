package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.PrivateFiles;
import io.knura.core.backup.BackupFile;
import io.knura.core.backup.BackupRecord;
import io.knura.core.crypto.Hex;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The admin API's requests for the setup page: signing in, and changing names, tags and actions. */
class SetupApiTest {
    private static final String UID = "04DE5F1EACC040";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final AdminServerTest.MutableClock clock = new AdminServerTest.MutableClock(Instant.parse("2026-11-01T00:00:00Z"));
    private final SecureRandom random = new SecureRandom();
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    private Path knurdFile;
    private String laptop;
    private String phone;
    private AuditLog audit;
    private AdminServer admin;
    private LiveActions live;
    private HttpServer hook;

    @BeforeEach
    void start() throws Exception {
        knurdFile = dir.resolve("knurd.properties");
        PrivateFiles.create(knurdFile, "# my settings\nmaster.key=00112233445566778899AABBCCDDEEFF\n");
        laptop = AdminTokens.generate(random);
        phone = AdminTokens.generate(random);
        Path tokensFile = dir.resolve("tokens.properties");
        PrivateFiles.create(tokensFile, AdminTokens.format(Map.of(
                "laptop", AdminTokens.hash(laptop), "fold7", AdminTokens.hash(phone))));
        audit = new AuditLog(dir.resolve("audit.log"), clock);
        live = new LiveActions(null, Clock.systemUTC());
        HomeSettings settings = new HomeSettings(knurdFile, null, live, Clock.systemUTC());
        NfcRelay nfc = new NfcRelay(TagKeys.derived(Hex.decode("00112233445566778899AABBCCDDEEFF"), Map.of()),
                "https://desk.tail1234.ts.net/tap", Set.of("fold7"), Duration.ofSeconds(2));
        admin = new AdminServer("127.0.0.1", 0, null, new TokenStore(tokensFile), audit, new MemoryCounterStore(),
                new RecentTaps(clock), new AdminServer.Info("master", null, null, 0, clock.instant()), clock, nfc);
        admin.manage(new AdminServer.Manage(new AdminPassword(dir.resolve(AdminPassword.FILE), random, 1000),
                new Sessions(clock), settings));
        admin.start();

        hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/", ex -> {
            received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        hook.start();
    }

    @AfterEach
    void stop() throws IOException {
        admin.close();
        audit.close();
        live.close();
        hook.stop(0);
    }

    @Test
    void theFirstPasswordIsSetHereAndSignsYouIn() throws Exception {
        assertEquals("{\"passwordSet\":false,\"signedIn\":false}", send("GET", "/v1/session", null, null).body());
        assertEquals(400, send("POST", "/v1/password", "{\"password\":\"short\"}", null).statusCode());
        HttpResponse<String> set = send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null);
        assertEquals(200, set.statusCode(), set.body());
        String cookie = cookie(set);
        assertTrue(set.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly; SameSite=Strict"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(AdminPassword.FILE))));
        assertFalse(Files.readString(dir.resolve(AdminPassword.FILE)).contains("lantern"), "only a hash is kept");

        assertEquals("{\"passwordSet\":true,\"signedIn\":true}", send("GET", "/v1/session", null, cookie).body());
        assertEquals("{\"token\":\"browser\",\"security\":\"token\"}", send("GET", "/v1/whoami", null, cookie).body());
        HttpResponse<String> again = send("POST", "/v1/password", "{\"password\":\"another-one-99\"}", null);
        assertEquals(401, again.statusCode(), "once set, changing it needs a sign-in");
    }

    @Test
    void signInSignOutAndWrongPasswords() throws Exception {
        assertEquals(409, send("POST", "/v1/session", "{\"password\":\"anything-at-all\"}", null).statusCode());
        send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null);
        assertEquals(401, send("POST", "/v1/session", "{\"password\":\"wrong-password\"}", null).statusCode());
        HttpResponse<String> in = send("POST", "/v1/session", "{\"password\":\"lantern-orbit-42\"}", null);
        assertEquals(200, in.statusCode());
        String cookie = cookie(in);
        assertEquals(200, send("GET", "/v1/status", null, cookie).statusCode());
        assertEquals(200, send("DELETE", "/v1/session", null, cookie).statusCode());
        assertEquals(401, send("GET", "/v1/status", null, cookie).statusCode(), "signed out");

        for (int i = 0; i < 4; i++) {
            send("POST", "/v1/session", "{\"password\":\"wrong-password\"}", null);
        }
        assertEquals(429, send("POST", "/v1/session", "{\"password\":\"lantern-orbit-42\"}", null).statusCode(),
                "too many wrong passwords lock the address out for a while");
        String log = Files.readString(dir.resolve("audit.log"));
        assertFalse(log.contains("lantern") || log.contains("knurs_"), "no passwords or session ids in the audit log");
    }

    @Test
    void sessionsExpireWhenIdle() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        clock.advance(Sessions.IDLE.minusMinutes(1));
        assertEquals(200, send("GET", "/v1/settings", null, cookie).statusCode());
        clock.advance(Sessions.IDLE.minusMinutes(1));
        assertEquals(200, send("GET", "/v1/settings", null, cookie).statusCode(), "use keeps it alive");
        clock.advance(Sessions.IDLE);
        assertEquals(401, send("GET", "/v1/settings", null, cookie).statusCode());
    }

    @Test
    void otherSitesCantUseTheBrowsersSession() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(uri("/v1/settings"))
                .header("Cookie", Sessions.COOKIE + "=" + cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Evil\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noHeader.statusCode());
        HttpResponse<String> otherSite = http.send(HttpRequest.newBuilder(uri("/v1/settings"))
                .header("Cookie", Sessions.COOKIE + "=" + cookie).header(AdminServer.REQUEST_HEADER, "1")
                .header("Origin", "https://evil.example")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Evil\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, otherSite.statusCode());
        HttpResponse<String> sameSite = http.send(HttpRequest.newBuilder(uri("/v1/settings"))
                .header("Cookie", Sessions.COOKIE + "=" + cookie).header(AdminServer.REQUEST_HEADER, "1")
                .header("Origin", "http://127.0.0.1:" + admin.port())
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Our house\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sameSite.statusCode(), sameSite.body());
        assertFalse(Files.readString(knurdFile).contains("Evil"));
    }

    @Test
    void nameIsSavedInKnurdPropertiesKeepingComments() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        assertEquals("{\"name\":null,\"passwordSet\":true,\"phones\":[],\"publicUrl\":null}", send("GET", "/v1/settings", null, cookie).body());
        HttpResponse<String> r = send("PUT", "/v1/settings", "{\"name\":\"Our house\"}", cookie);
        assertEquals("{\"name\":\"Our house\",\"passwordSet\":true,\"phones\":[],\"publicUrl\":null}", r.body());
        String text = Files.readString(knurdFile);
        assertTrue(text.startsWith("# my settings\nmaster.key=00112233445566778899AABBCCDDEEFF\n"), text);
        assertTrue(text.contains("knura.name=Our house"), text);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(knurdFile)));
        assertEquals(400, send("PUT", "/v1/settings", "{\"name\":\"two\\nlines\"}", cookie).statusCode());
        assertTrue(send("GET", "/v1/status", null, cookie).body().contains("\"name\":\"Our house\""));
    }

    @Test
    void connectionsTagsAndATestTapWithoutARestart() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        String url = "http://127.0.0.1:" + hook.getAddress().getPort() + "/hook";

        HttpResponse<String> added = send("PUT", "/v1/connections/webhook/desk-hook", "{\"url\":\"" + url + "\"}", cookie);
        assertEquals(200, added.statusCode(), added.body());
        assertTrue(added.body().contains("\"secret\":\"whsec_"), "a new connector's secret is shown once");
        assertTrue(Files.readString(knurdFile).contains("actions.file=knurgn.properties"), "knurd now reads the actions file");
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve(HomeSettings.DEFAULT_SECRETS_FILE))));
        HttpResponse<String> changed = send("PUT", "/v1/connections/webhook/desk-hook", "{\"url\":\"" + url + "2\"}", cookie);
        assertTrue(changed.body().endsWith("\"secret\":null}"), "changing the address keeps the secret, unshown");

        assertEquals(200, send("PUT", "/v1/tags/" + UID, "{\"label\":\"Front door\",\"icon\":\"door\"}", cookie).statusCode());
        assertEquals(200, send("PUT", "/v1/tags/" + UID + "/actions", "{\"actions\":\"desk-hook\"}", cookie).statusCode());
        assertEquals("{\"tags\":[{\"uid\":\"" + UID + "\",\"lastCounter\":null,\"label\":\"Front door\",\"icon\":\"door\","
                + "\"actions\":[\"desk-hook\"]}]}", send("GET", "/v1/tags", null, cookie).body());
        assertEquals("{\"connections\":[{\"type\":\"webhook\",\"id\":\"desk-hook\",\"url\":\"" + url + "2\",\"usedBy\":[\""
                + UID + "\"]}]}", send("GET", "/v1/connections", null, cookie).body());

        assertEquals("{\"started\":1}", send("POST", "/v1/tags/" + UID + "/test", null, cookie).body());
        String body = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(body, "the webhook ran without a restart");
        assertTrue(body.contains("\"counter\":0") && body.contains("Front door"), body);

        assertEquals(409, send("DELETE", "/v1/connections/webhook/desk-hook", null, cookie).statusCode(), "still in use");
        assertEquals(200, send("PUT", "/v1/tags/" + UID + "/actions", "{\"actions\":\"\"}", cookie).statusCode());
        assertEquals(200, send("DELETE", "/v1/connections/webhook/desk-hook", null, cookie).statusCode());
        assertFalse(Files.readString(dir.resolve(HomeSettings.DEFAULT_SECRETS_FILE)).contains("desk-hook"));
        assertEquals("{\"started\":0}", send("POST", "/v1/tags/" + UID + "/test", null, cookie).body());
    }

    @Test
    void aBadChangeIsRefusedAndTheFilesStayAsTheyWere() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        send("PUT", "/v1/connections/webhook/ok", "{\"url\":\"https://hooks.example.com/a\"}", cookie);
        String rules = Files.readString(dir.resolve(HomeSettings.DEFAULT_ACTIONS_FILE));
        String secrets = Files.readString(dir.resolve(HomeSettings.DEFAULT_SECRETS_FILE));

        HttpResponse<String> plainHttp = send("PUT", "/v1/connections/webhook/bad", "{\"url\":\"http://hooks.example.com/b\"}", cookie);
        assertEquals(400, plainHttp.statusCode(), plainHttp.body());
        assertEquals(400, send("PUT", "/v1/tags/" + UID + "/actions", "{\"actions\":\"nothing-here\"}", cookie).statusCode());
        assertEquals(rules, Files.readString(dir.resolve(HomeSettings.DEFAULT_ACTIONS_FILE)));
        assertEquals(secrets, Files.readString(dir.resolve(HomeSettings.DEFAULT_SECRETS_FILE)));
        assertEquals(404, send("PUT", "/v1/tags/not-a-uid", "{\"label\":\"x\"}", cookie).statusCode());
        assertEquals(400, send("PUT", "/v1/connections/webhook/has%20space", "{\"url\":\"https://a.example\"}", cookie)
                .statusCode());
    }

    @Test
    void handEditsAreReloadedOnRequest() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        send("PUT", "/v1/connections/webhook/a", "{\"url\":\"https://hooks.example.com/a\"}", cookie);
        Path rules = dir.resolve(HomeSettings.DEFAULT_ACTIONS_FILE);
        Files.writeString(rules, Files.readString(rules) + "knot." + UID + ".actions=a\n");
        assertEquals("{\"knots\":1}", send("POST", "/v1/actions/reload", null, cookie).body());
        assertTrue(send("GET", "/v1/status", null, cookie).body().contains("\"knots\":1"));
        Files.writeString(rules, Files.readString(rules) + "nonsense=1\n");
        HttpResponse<String> bad = send("POST", "/v1/actions/reload", null, cookie);
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("unknown setting nonsense"), bad.body());
        assertEquals(1, live.knotCount(), "a bad file leaves the running actions alone");
    }

    @Test
    void onlyProvisionTokensMayChangeSettings() throws Exception {
        HttpResponse<String> laptopTry = http.send(HttpRequest.newBuilder(uri("/v1/settings"))
                .header("Authorization", "Bearer " + laptop).header(AdminServer.REQUEST_HEADER, "1")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, laptopTry.statusCode());
        HttpResponse<String> phoneTry = http.send(HttpRequest.newBuilder(uri("/v1/settings"))
                .header("Authorization", "Bearer " + phone).header(AdminServer.REQUEST_HEADER, "1")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Lake cabin\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, phoneTry.statusCode(), phoneTry.body());
        HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(uri("/v1/settings"))
                .header("Authorization", "Bearer " + phone)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noHeader.statusCode(), "changes always need the header, whoever asks");
    }

    @Test
    void changingThePasswordSignsEveryoneElseOut() throws Exception {
        String first = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        String second = cookie(send("POST", "/v1/session", "{\"password\":\"lantern-orbit-42\"}", null));
        assertEquals(401, send("POST", "/v1/password", "{\"current\":\"nope-nope-nope\",\"password\":\"another-one-99\"}", first)
                .statusCode());
        HttpResponse<String> changed = send("POST", "/v1/password",
                "{\"current\":\"lantern-orbit-42\",\"password\":\"another-one-99\"}", first);
        assertEquals(200, changed.statusCode(), changed.body());
        String renewed = cookie(changed);
        assertEquals(401, send("GET", "/v1/settings", null, second).statusCode());
        assertEquals(401, send("GET", "/v1/settings", null, first).statusCode());
        assertEquals(200, send("GET", "/v1/settings", null, renewed).statusCode());
    }

    @Test
    void withoutManageTheOldApiIsUnchanged() throws Exception {
        try (AdminServer plain = new AdminServer("127.0.0.1", 0, null, new TokenStore(dir.resolve("tokens.properties")), audit,
                new MemoryCounterStore(), new RecentTaps(clock), new AdminServer.Info("master", null, null, 0, clock.instant()),
                clock)) {
            plain.start();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + plain.port() + "/v1/session"))
                    .header("Authorization", "Bearer " + laptop).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, r.statusCode());
        }
    }

    @Test
    void theSetupPageIsServedWithoutSignInAndWithAStrictPolicy() throws Exception {
        for (String path : new String[] {"/", "/setup", "/setup/app.js", "/setup/app.css", "/setup/words.js", "/setup/icon.svg"}) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), path);
            assertEquals(SetupPage.CSP, r.headers().firstValue("Content-Security-Policy").orElseThrow(), path);
            assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElseThrow(), path);
        }
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(uri("/setup")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertFalse(page.body().contains("http"), "nothing is loaded from the internet");
        assertEquals(401, http.send(HttpRequest.newBuilder(uri("/setup/knurd.properties")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "only the page's own files are served");
        assertEquals(401, http.send(HttpRequest.newBuilder(uri("/v1/status")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "the API itself still needs a sign-in");
    }

    @Test
    void theRecoveryKitDownloadsAndIsRecorded() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        assertEquals(400, send("POST", "/v1/backup/file", "{\"passphrase\":\"short\"}", cookie).statusCode());
        HttpResponse<byte[]> file = sendBytes("/v1/backup/file", "{\"passphrase\":\"lamp orbit maple tide crow fern\"}", cookie);
        assertEquals(200, file.statusCode());
        assertTrue(file.headers().firstValue("Content-Disposition").orElseThrow().contains(".knurabackup\""));
        BackupFile.Contents contents = BackupFile.open(file.body(), "lamp orbit maple tide crow fern".toCharArray());
        assertTrue(new String(contents.files().get("knurd.properties"), StandardCharsets.UTF_8)
                .contains("master.key=00112233445566778899AABBCCDDEEFF"));

        HttpResponse<byte[]> sheet = sendBytes("/v1/backup/sheet", null, cookie);
        assertEquals(200, sheet.statusCode());
        assertTrue(sheet.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertTrue(new String(sheet.body(), StandardCharsets.UTF_8).contains("<html"));

        BackupRecord.Status record = BackupRecord.read(dir, BackupRecord.keyId(Hex.decode("00112233445566778899AABBCCDDEEFF")));
        assertTrue(record.file().isPresent() && record.sheet().isPresent(), "both copies are recorded");
        String log = Files.readString(dir.resolve("audit.log"));
        assertFalse(log.contains("lamp orbit"), "the passphrase is never logged");
        assertEquals(401, sendBytes("/v1/backup/sheet", null, null).statusCode(), "needs a sign-in");
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/v1/backup/sheet")).header("Authorization", "Bearer " + laptop)
                .POST(HttpRequest.BodyPublishers.noBody()).header(AdminServer.REQUEST_HEADER, "1").build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "read-only tokens can't take the master key");
    }

    @Test
    void reachSaysWhenThereIsNoAddress() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        String reach = send("GET", "/v1/reach", null, cookie).body();
        assertTrue(reach.contains("\"reachable\":false") && reach.contains("public.url is not set"), reach);
        Files.writeString(knurdFile, "admin.provision.tokens=fold7\n", java.nio.file.StandardOpenOption.APPEND);
        String settings = send("GET", "/v1/settings", null, cookie).body();
        assertTrue(settings.endsWith(",\"passwordSet\":true,\"phones\":[\"fold7\"],\"publicUrl\":null}"), settings);
    }

    @Test
    void theAddressPhonesUseIsSavedAndCheckedBeforeARestart() throws Exception {
        String cookie = cookie(send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}", null));
        assertEquals(400, send("PUT", "/v1/settings", "{\"publicUrl\":\"http://desk.example.com\"}", cookie).statusCode(),
                "plain http on the internet is refused");
        HttpResponse<String> r = send("PUT", "/v1/settings", "{\"publicUrl\":\"https://desk.tail1234.ts.net/\"}", cookie);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"publicUrl\":\"https://desk.tail1234.ts.net\""), r.body());
        assertTrue(Files.readString(knurdFile).contains("public.url=https://desk.tail1234.ts.net\n"));
        assertTrue(Files.readString(knurdFile).startsWith("# my settings\n"), "comments stay");
        String reach = send("GET", "/v1/reach", null, cookie).body();
        assertTrue(reach.contains("\"publicUrl\":\"https://desk.tail1234.ts.net\"") && reach.contains("\"restart\":true"), reach);
        String ts = send("GET", "/v1/tailscale", null, cookie).body();
        assertTrue(ts.startsWith("{\"state\":\"NotInstalled\""), ts);
    }

    // ---- helpers ----

    private HttpResponse<byte[]> sendBytes(String path, String json, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header(AdminServer.REQUEST_HEADER, "1")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (session != null) {
            b.header("Cookie", Sessions.COOKIE + "=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + admin.port() + path);
    }

    /** A request as the setup page sends it, with the session cookie if given. */
    private HttpResponse<String> send(String method, String path, String json, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header(AdminServer.REQUEST_HEADER, "1")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (session != null) {
            b.header("Cookie", Sessions.COOKIE + "=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<String> r) {
        String header = r.headers().firstValue("Set-Cookie").orElse(null);
        assertNotNull(header, "no Set-Cookie: " + r.statusCode() + " " + r.body());
        String value = Sessions.fromCookie(header.split(";")[0]);
        assertNull(value.isEmpty() ? "empty" : null);
        return value;
    }
}
