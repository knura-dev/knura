package io.knura.knurctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.knura.core.admin.AdminKeystore;
import io.knura.core.admin.AdminTokens;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminCommandsTest {
    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        out.reset();
        err.reset();
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path config() {
        return dir.resolve("knurd.properties");
    }

    private Properties load(Path file) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(Files.readString(file)));
        return p;
    }

    private static String perms(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    @Test
    void tokenAddTurnsTheApiOnAndWritesAPrivateCreds() throws Exception {
        assertEquals(0, run("init", "--config", config().toString()));
        Path cc = dir.resolve("laptop.properties");
        assertEquals(0, run("admin", "token", "add", "laptop", "--config", config().toString(), "--out", cc.toString()), err());
        assertTrue(out().contains("Restart knurd"), out());

        assertEquals("knurd-admin-tokens.properties", load(config()).getProperty("admin.tokens.file"));
        Path tokensFile = dir.resolve("knurd-admin-tokens.properties");
        assertEquals("rw-------", perms(cc));
        assertEquals("rw-------", perms(tokensFile));

        Creds loaded = Creds.load(cc);
        assertEquals("http://127.0.0.1:8425", loaded.url().toString());
        assertNull(loaded.fingerprint());
        assertEquals(AdminTokens.hash(loaded.token()), load(tokensFile).getProperty("laptop"));
        assertFalse(Files.readString(tokensFile).contains(loaded.token()), "knurd's file holds only the hash");

        assertEquals(2, run("admin", "token", "add", "laptop", "--config", config().toString(),
                "--out", dir.resolve("other.properties").toString()));
        assertTrue(err().contains("already a token named laptop"), err());
        assertEquals(2, run("admin", "token", "add", "phone", "--config", config().toString(), "--out", cc.toString()));
        assertTrue(err().contains("already exists"), err());
        assertEquals(Creds.load(cc).token(), loaded.token(), "never overwrites a creds file");

        assertEquals(0, run("admin", "token", "add", "phone", "--config", config().toString(),
                "--out", dir.resolve("phone.properties").toString()));
        assertTrue(out().contains("straight away"), out());
        assertEquals(0, run("admin", "token", "list", "--config", config().toString()));
        assertEquals(List.of("laptop", "phone"), out().lines().toList());
        assertEquals(0, run("admin", "token", "remove", "laptop", "--config", config().toString()));
        assertEquals(0, run("admin", "token", "list", "--config", config().toString()));
        assertEquals(List.of("phone"), out().lines().toList());
        assertEquals(2, run("admin", "token", "remove", "laptop", "--config", config().toString()));
    }

    @Test
    void tokenNamesAndUsage() throws Exception {
        assertEquals(0, run("init", "--config", config().toString()));
        assertEquals(2, run("admin", "token", "add", "My Laptop", "--config", config().toString()));
        assertEquals(2, run("admin", "token", "add", "--config", config().toString()));
        assertEquals(2, run("admin", "frobnicate"));
        assertEquals(2, run("admin", "token", "add", "laptop", "--config", dir.resolve("missing.properties").toString()));
        assertTrue(err().contains("knurd's computer"), err());
    }

    @Test
    void lanAccessNeedsTls() throws Exception {
        assertEquals(0, run("init", "--config", config().toString()));
        assertEquals(2, run("admin", "token", "add", "laptop", "--config", config().toString(),
                "--admin-url", "http://192.168.1.20:8425", "--out", dir.resolve("cc").toString()));
        assertTrue(err().contains("https"), err());
        assertEquals(2, run("admin", "token", "add", "laptop", "--config", config().toString(),
                "--admin-url", "https://192.168.1.20:8425", "--out", dir.resolve("cc").toString()));
        assertTrue(err().contains("tls create"), err());
        assertFalse(Files.exists(dir.resolve("cc")));
    }

    @Test
    void tlsCreateThenTokenPinsTheCertificate() throws Exception {
        assertEquals(0, run("init", "--config", config().toString()));
        assertEquals(0, run("admin", "tls", "create", "--config", config().toString()), err());
        Path keystore = dir.resolve("knurd-admin.p12");
        Path password = dir.resolve("knurd-admin.p12.password");
        assertEquals("rw-------", perms(keystore));
        assertEquals("rw-------", perms(password));
        Properties settings = load(config());
        assertEquals("knurd-admin.p12", settings.getProperty("admin.tls.keystore"));
        assertEquals("knurd-admin.p12.password", settings.getProperty("admin.tls.password.file"));
        String fingerprint = AdminKeystore.load(keystore, password).fingerprint();
        assertTrue(out().contains(fingerprint), out());
        assertEquals(2, run("admin", "tls", "create", "--config", config().toString()), "never replaces a certificate");

        Path cc = dir.resolve("lan.properties");
        assertEquals(0, run("admin", "token", "add", "laptop", "--config", config().toString(),
                "--admin-url", "https://192.168.1.20:8425", "--out", cc.toString()), err());
        Creds loaded = Creds.load(cc);
        assertEquals("https://192.168.1.20:8425", loaded.url().toString());
        assertEquals(fingerprint, loaded.fingerprint());
    }

    // ---- --creds ----

    private Path creds(String text) throws IOException {
        Path file = dir.resolve("cc-" + Math.abs(text.hashCode()) + ".properties");
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private static final String TOKEN = "knurd_" + "A".repeat(43);

    @Test
    void credsMustBePrivate() throws Exception {
        Path file = creds("knurd.url=http://127.0.0.1:8425\nknurd.token=" + TOKEN + "\n");
        assertEquals(TOKEN, Creds.load(file).token());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        Main.UsageException e = assertThrows(Main.UsageException.class, () -> Creds.load(file));
        assertTrue(e.getMessage().contains("chmod 600"), e.getMessage());
        assertEquals(2, run("admin", "status", "--creds", file.toString()));
        assertTrue(err().contains("chmod 600"), err());
    }

    @Test
    void credsNeedsHttpsOffThisComputer() throws Exception {
        for (String url : new String[] {"http://192.168.1.20:8425", "http://localhost.evil.example:8425",
                "https://192.168.1.20:8425/admin", "https://user:pw@192.168.1.20:8425", "ftp://127.0.0.1", ""}) {
            Path file = creds("knurd.url=" + url + "\nknurd.token=" + TOKEN + "\n");
            assertThrows(Main.UsageException.class, () -> Creds.load(file), url);
        }
        Creds.load(creds("knurd.url=http://localhost:8425/\nknurd.token=" + TOKEN + "\n"));
        Creds.load(creds("knurd.url=https://192.168.1.20:8425\nknurd.token=" + TOKEN + "\n"));
    }

    @Test
    void credsRejectsTyposAndBadValues() throws Exception {
        String ok = "knurd.url=https://192.168.1.20:8425\nknurd.token=" + TOKEN + "\n";
        assertThrows(Main.UsageException.class, () -> Creds.load(creds(ok + "token=x\n")));
        assertThrows(Main.UsageException.class, () -> Creds.load(creds(
                "knurd.url=https://192.168.1.20:8425\nknurd.token=hunter2\n")));
        assertThrows(Main.UsageException.class, () -> Creds.load(creds(ok + "knurd.fingerprint=abc\n")));
        assertThrows(Main.UsageException.class, () -> Creds.load(creds(
                "knurd.url=http://127.0.0.1:8425\nknurd.token=" + TOKEN + "\nknurd.fingerprint=" + "a".repeat(64) + "\n")));
        assertThrows(Main.UsageException.class, () -> Creds.load(dir.resolve("missing.properties")));
    }

    // ---- talking to knurd ----

    /** A stand-in for knurd's admin API that remembers the Authorization header it got. */
    private HttpServer fakeKnurd(HttpServer server, AtomicReference<String> auth) {
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().toString();
            String body = switch (path) {
                case "/v1/whoami" -> "{\"token\":\"laptop\"}";
                case "/v1/status" -> "{\"status\":\"up\",\"uptimeSeconds\":93784,\"keys\":\"master\",\"publicUrl\":null,"
                        + "\"resolverOwner\":\"rq5tw8np3a\",\"knots\":2,\"tags\":1}";
                case "/v1/tags" -> "{\"tags\":[{\"uid\":\"04DE5F1EACC040\",\"lastCounter\":61}]}";
                case "/v1/taps?limit=5" -> "{\"taps\":[{\"time\":\"2026-10-15T00:00:00Z\",\"status\":\"replay\","
                        + "\"uid\":\"04DE5F1EACC040\",\"counter\":61,\"actions\":0},{\"time\":\"2026-10-15T00:00:00Z\","
                        + "\"status\":\"malformed\",\"uid\":null,\"actions\":0}]}";
                case "/v1/redirect" -> null;
                default -> "{\"status\":\"not_found\"}";
            };
            if (body == null) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/steal");
                send(exchange, 302, "{\"status\":\"moved\"}");
            } else {
                send(exchange, body.startsWith("{\"status\":\"not_found") ? 404 : 200, body);
            }
        });
        server.start();
        return server;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void adminCommandsOverHttpOnThisComputer() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = fakeKnurd(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0), auth);
        try {
            Path cc = creds("knurd.url=http://127.0.0.1:" + server.getAddress().getPort() + "\nknurd.token=" + TOKEN + "\n");
            assertEquals(0, run("admin", "whoami", "--creds", cc.toString()), err());
            assertTrue(out().contains("as token \"laptop\""), out());
            assertEquals("Bearer " + TOKEN, auth.get());

            assertEquals(0, run("admin", "status", "--creds", cc.toString()), err());
            assertTrue(out().contains("knurd is up (for 1d 2h 3m)"), out());
            assertTrue(out().contains("owner rq5tw8np3a"), out());
            assertEquals(0, run("admin", "tags", "--creds", cc.toString()), err());
            assertTrue(out().contains("04DE5F1EACC040   61"), out());
            assertEquals(0, run("admin", "taps", "--limit", "5", "--creds", cc.toString()), err());
            assertEquals(3, out().lines().count(), out());
            assertTrue(out().contains("malformed"), out());

            AdminClient client = new AdminClient(Creds.load(cc));
            IOException redirect = assertThrows(IOException.class, () -> client.get("/v1/redirect"));
            assertTrue(redirect.getMessage().contains("302"), "redirects are never followed: " + redirect.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plaintextModeNeedsNoCreds() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>("unset");
        HttpServer server = fakeKnurd(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0), auth);
        try {
            Files.writeString(config(), "master.key=00112233445566778899AABBCCDDEEFF\nadmin.security=plaintext\n"
                    + "admin.listen.port=" + server.getAddress().getPort() + "\n");
            assertEquals(0, run("admin", "status", "--config", config().toString()), err());
            assertTrue(out().contains("knurd is up"), out());
            assertNull(auth.get(), "no token is sent in plaintext mode");
            assertEquals(0, run("admin", "taps", "--limit", "5", "--config", config().toString()), err());

            assertEquals(2, run("admin", "token", "add", "laptop", "--config", config().toString(),
                    "--out", dir.resolve("cc").toString()));
            assertTrue(err().contains("plaintext"), err());

            Files.writeString(config(), "master.key=00112233445566778899AABBCCDDEEFF\nadmin.security=plaintext\n"
                    + "admin.listen.host=192.168.1.20\n");
            assertEquals(2, run("admin", "status", "--config", config().toString()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tokenModeWithoutCredsExplainsBothOptions() throws Exception {
        Files.writeString(config(), "master.key=00112233445566778899AABBCCDDEEFF\nadmin.tokens.file=t.properties\n");
        assertEquals(2, run("admin", "status", "--config", config().toString()));
        assertTrue(err().contains("--creds") && err().contains("admin.security=plaintext"), err());
    }

    @Test
    void pinsTheCertificate() throws Exception {
        assertEquals(0, run("init", "--config", config().toString()));
        assertEquals(0, run("admin", "tls", "create", "--config", config().toString()), err());
        AdminKeystore ks = AdminKeystore.load(dir.resolve("knurd-admin.p12"), dir.resolve("knurd-admin.p12.password"));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks.keyStore(), ks.password());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls));
        AtomicReference<String> auth = new AtomicReference<>();
        fakeKnurd(https, auth);
        try {
            String url = "https://127.0.0.1:" + https.getAddress().getPort();
            Path good = creds("knurd.url=" + url + "\nknurd.token=" + TOKEN + "\nknurd.fingerprint=" + ks.fingerprint() + "\n");
            assertEquals(Map.of("token", "laptop"), new AdminClient(Creds.load(good)).get("/v1/whoami"));

            String other = ks.fingerprint().startsWith("0") ? "1" + ks.fingerprint().substring(1) : "0" + ks.fingerprint().substring(1);
            Path wrong = creds("knurd.url=" + url + "\nknurd.token=" + TOKEN + "\nknurd.fingerprint=" + other + "\n");
            auth.set(null);
            assertEquals(1, run("admin", "whoami", "--creds", wrong.toString()));
            assertTrue(err().contains("doesn't match knurd.fingerprint"), err());
            assertNull(auth.get(), "the token is never sent to an unpinned server");

            Path unpinned = creds("knurd.url=" + url + "\nknurd.token=" + TOKEN + "\n");
            assertEquals(1, run("admin", "whoami", "--creds", unpinned.toString()));
            assertTrue(err().contains("knurd.fingerprint"), err());
            assertNull(auth.get());
        } finally {
            https.stop(0);
        }
    }

    @Test
    void miniJson() {
        assertEquals(Map.of("a", List.of(1L, "x\"y", true), "b", Map.of()), MiniJson.parse("{\"a\":[1,\"x\\\"y\",true],\"b\":{}}"));
        assertNull(MiniJson.parse(" null "));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("<html>"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[".repeat(40)));
    }
}
