package io.knura.knurd;

import static io.knura.knurd.Taps.ZERO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.admin.AdminKeystore;
import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.CertFingerprint;
import io.knura.core.admin.PrivateFiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminServerTest {
    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
    private final SecureRandom random = new SecureRandom();
    private final MemoryCounterStore counters = new MemoryCounterStore();
    private final RecentTaps recent = new RecentTaps(clock);
    private Path tokensFile;
    private Path auditFile;
    private String token;
    private KnurdServer tapServer;
    private AdminServer admin;
    private AuditLog audit;

    @BeforeEach
    void start() throws Exception {
        tokensFile = dir.resolve("tokens.properties");
        auditFile = dir.resolve("audit.log");
        token = AdminTokens.generate(random);
        PrivateFiles.create(tokensFile, AdminTokens.format(Map.of("laptop", AdminTokens.hash(token))));
        TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), counters);
        tapServer = new KnurdServer("127.0.0.1", 0, verifier, (uid, ctr) -> 1);
        tapServer.recordTapsTo(recent);
        tapServer.start();
        admin = startAdmin(null);
    }

    private AdminServer startAdmin(SSLContext tls) throws IOException {
        return startAdmin(tls, new TokenStore(tokensFile));
    }

    private AdminServer startAdmin(SSLContext tls, TokenStore tokens) throws IOException {
        audit = new AuditLog(auditFile, clock);
        AdminServer server = new AdminServer("127.0.0.1", 0, tls, tokens, audit, counters, recent,
                new AdminServer.Info("master", "https://desk.tail1234.ts.net", null, 3, clock.instant()), clock);
        server.start();
        return server;
    }

    @AfterEach
    void stop() throws IOException {
        admin.close();
        tapServer.close();
        audit.close();
    }

    @Test
    void needsAToken() throws Exception {
        HttpResponse<String> none = get("/v1/whoami", null);
        assertEquals(401, none.statusCode());
        assertEquals("Bearer realm=\"knurd\"", none.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(401, get("/v1/whoami", AdminTokens.generate(random)).statusCode());
        assertEquals(401, get("/v1/whoami", "not-a-token").statusCode());

        HttpResponse<String> ok = get("/v1/whoami", token);
        assertEquals(200, ok.statusCode());
        assertEquals("{\"token\":\"laptop\",\"security\":\"token\"}", ok.body());
        assertEquals("no-store", ok.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void unknownPathsAndMethods() throws Exception {
        assertEquals(404, get("/v1/nothing", token).statusCode());
        assertEquals(401, get("/v1/nothing", null).statusCode(), "nothing is revealed before logging in");
        HttpResponse<String> post = http.send(request("/v1/status", token).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
        assertEquals(400, get("/v1/taps?limit=0", token).statusCode());
        assertEquals(400, get("/v1/taps?limit=201", token).statusCode());
        assertEquals(400, get("/v1/taps?limit=x", token).statusCode());
    }

    @Test
    void refusesProxiedRequestsEvenWithAGoodToken() throws Exception {
        for (String header : AdminServer.PROXY_HEADERS) {
            HttpResponse<String> r = http.send(request("/v1/status", token).header(header, "203.0.113.9").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), header);
            assertTrue(r.body().contains("proxy or tunnel"), r.body());
        }
    }

    @Test
    void statusTagsAndTaps() throws Exception {
        tapGet("/tap?" + Taps.query(Taps.AN12196));
        tapGet("/tap?" + Taps.query(Taps.AN12196));
        tapGet("/tap?c=1");
        clock.advance(Duration.ofSeconds(90));

        assertEquals("{\"status\":\"up\",\"uptimeSeconds\":90,\"keys\":\"master\",\"publicUrl\":\"https://desk.tail1234.ts.net\","
                + "\"resolverOwner\":null,\"knots\":3,\"tags\":1}", get("/v1/status", token).body());
        assertEquals("{\"tags\":[{\"uid\":\"04DE5F1EACC040\",\"lastCounter\":61}]}", get("/v1/tags", token).body());
        String t = "2026-10-15T00:00:00Z";
        assertEquals("{\"taps\":["
                + "{\"time\":\"" + t + "\",\"status\":\"malformed\",\"uid\":null,\"actions\":0},"
                + "{\"time\":\"" + t + "\",\"status\":\"replay\",\"uid\":\"04DE5F1EACC040\",\"counter\":61,\"actions\":0},"
                + "{\"time\":\"" + t + "\",\"status\":\"ok\",\"uid\":\"04DE5F1EACC040\",\"counter\":61,\"actions\":1}]}",
                get("/v1/taps", token).body());
        assertEquals(1, get("/v1/taps?limit=1", token).body().split("\"time\"").length - 1);
    }

    @Test
    void auditsEveryRequestWithoutSecrets() throws Exception {
        get("/v1/status", null);
        get("/v1/status", token);
        http.send(request("/v1/status", token).header("X-Forwarded-For", "203.0.113.9").build(),
                HttpResponse.BodyHandlers.ofString());
        String log = Files.readString(auditFile);
        String[] lines = log.split("\n");
        assertEquals(3, lines.length, log);
        assertEquals("{\"time\":\"2026-10-15T00:00:00Z\",\"remote\":\"127.0.0.1\",\"token\":null,\"method\":\"GET\","
                + "\"path\":\"/v1/status\",\"status\":401,\"detail\":\"bad or missing token\"}", lines[0]);
        assertTrue(lines[1].contains("\"token\":\"laptop\"") && lines[1].contains("\"status\":200"), lines[1]);
        assertTrue(lines[2].contains("\"status\":403") && lines[2].contains("X-Forwarded-For"), lines[2]);
        assertFalse(log.contains(token), "the audit log never holds tokens");
        assertFalse(log.contains("knurd_"), "the audit log never holds tokens");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(auditFile)));
    }

    @Test
    void refusesWhenTheAuditLogCantBeWritten() throws Exception {
        audit.close();
        HttpResponse<String> r = get("/v1/status", token);
        assertEquals(500, r.statusCode());
        assertTrue(r.body().contains("audit log"), r.body());
    }

    @Test
    void locksOutGuessingButNotTheRealToken() throws Exception {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertEquals(401, get("/v1/status", AdminTokens.generate(random)).statusCode());
        }
        HttpResponse<String> locked = get("/v1/status", AdminTokens.generate(random));
        assertEquals(429, locked.statusCode());
        assertTrue(locked.headers().firstValue("Retry-After").isPresent());
        assertEquals(200, get("/v1/status", token).statusCode(), "a good token is never locked out");
        assertEquals(401, get("/v1/status", AdminTokens.generate(random)).statusCode(), "a good token clears the count");
    }

    @Test
    void lockoutEnds() throws Exception {
        for (int i = 0; i <= LoginThrottle.MAX_FAILURES; i++) {
            get("/v1/status", null);
        }
        assertEquals(429, get("/v1/status", null).statusCode());
        clock.advance(LoginThrottle.LOCKOUT.plusSeconds(1));
        assertEquals(401, get("/v1/status", null).statusCode());
    }

    @Test
    void picksUpTokenChangesWithoutRestart() throws Exception {
        String second = AdminTokens.generate(random);
        PrivateFiles.replace(tokensFile, AdminTokens.format(Map.of("loomstudio", AdminTokens.hash(second))));
        assertEquals("{\"token\":\"loomstudio\",\"security\":\"token\"}", get("/v1/whoami", second).body());
        assertEquals(401, get("/v1/whoami", token).statusCode(), "removed tokens stop working at once");
    }

    @Test
    void locksTheApiWhenTheTokensFileIsUnsafe() throws Exception {
        Files.setPosixFilePermissions(tokensFile, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setLastModifiedTime(tokensFile, java.nio.file.attribute.FileTime.from(clock.instant().plusSeconds(5)));
        assertEquals(401, get("/v1/whoami", token).statusCode());
        Files.setPosixFilePermissions(tokensFile, PosixFilePermissions.fromString("rw-------"));
        assertEquals(200, get("/v1/whoami", token).statusCode());
        Files.delete(tokensFile);
        assertEquals(401, get("/v1/whoami", token).statusCode());
    }

    @Test
    void wontStartWithAnUnsafeTokensFile() throws Exception {
        Files.setPosixFilePermissions(tokensFile, PosixFilePermissions.fromString("rw-rw-rw-"));
        IOException e = assertThrows(IOException.class, () -> new TokenStore(tokensFile));
        assertTrue(e.getMessage().contains("chmod 600"), e.getMessage());
    }

    @Test
    void servesTlsWithTheKeystoreCertificate() throws Exception {
        Path keystore = dir.resolve("admin.p12");
        Path password = dir.resolve("admin.p12.password");
        makeKeystore(keystore, password);
        AdminKeystore ks = AdminKeystore.load(keystore, password);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks.keyStore(), ks.password());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        admin.close();
        audit.close();
        admin = startAdmin(tls);

        // Trust exactly this certificate, as knurctl's fingerprint pin does.
        X509Certificate cert = (X509Certificate) ks.keyStore().getCertificate("knurd-admin");
        assertEquals(ks.fingerprint(), CertFingerprint.of(cert));
        java.security.KeyStore trust = java.security.KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("knurd", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        HttpClient https = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).sslContext(client).build();
        HttpResponse<String> r = https.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + admin.port() + "/v1/whoami"))
                        .header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("{\"token\":\"laptop\",\"security\":\"token\"}", r.body());
        assertThrows(IOException.class, () -> get("/v1/whoami", token), "plain HTTP gets nothing from a TLS port");
    }

    @Test
    void keystoreMustBePrivate() throws Exception {
        Path keystore = dir.resolve("admin.p12");
        Path password = dir.resolve("admin.p12.password");
        makeKeystore(keystore, password);
        Files.setPosixFilePermissions(keystore, PosixFilePermissions.fromString("rw-r--r--"));
        IOException e = assertThrows(IOException.class, () -> AdminKeystore.load(keystore, password));
        assertTrue(e.getMessage().contains("chmod 600"), e.getMessage());
    }

    private void restartInPlaintextMode() throws IOException {
        admin.close();
        audit.close();
        admin = startAdmin(null, null);
    }

    @Test
    void plaintextModeNeedsNoLogin() throws Exception {
        restartInPlaintextMode();
        HttpResponse<String> me = get("/v1/whoami", null);
        assertEquals(200, me.statusCode());
        assertEquals("{\"token\":null,\"security\":\"plaintext\"}", me.body());
        assertEquals(200, get("/v1/status", null).statusCode());
        assertEquals(200, get("/v1/status", "anything").statusCode(), "a token is simply ignored");
        assertEquals(404, get("/v1/nothing", null).statusCode());
        String log = Files.readString(auditFile);
        assertTrue(log.contains("\"detail\":\"plaintext: no login\""), log);
    }

    @Test
    void plaintextModeStillRefusesProxiesAndOtherHostNames() throws Exception {
        restartInPlaintextMode();
        HttpResponse<String> proxied = http.send(request("/v1/status", null).header("X-Forwarded-For", "203.0.113.9").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, proxied.statusCode());
        // A web page that points its own name at 127.0.0.1 (DNS rebinding) sends that name as Host.
        assertTrue(AdminServer.loopbackHost("127.0.0.1:8425"));
        assertTrue(AdminServer.loopbackHost("localhost:8425"));
        assertTrue(AdminServer.loopbackHost("[::1]:8425"));
        assertTrue(AdminServer.loopbackHost("localhost"));
        assertFalse(AdminServer.loopbackHost("evil.example:8425"));
        assertFalse(AdminServer.loopbackHost("127.0.0.1.evil.example"));
        assertFalse(AdminServer.loopbackHost(null));
    }

    static void makeKeystore(Path keystore, Path password) throws Exception {
        PrivateFiles.create(password, "test-password\n");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "knurd-admin", "-keyalg", "EC",
                "-groupname", "secp256r1", "-validity", "2", "-dname", "CN=test", "-ext", "SAN=ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass:file", password.toString(),
                "-keypass:file", password.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, out);
        Files.setPosixFilePermissions(keystore, PosixFilePermissions.fromString("rw-------"));
    }

    private HttpRequest.Builder request(String path, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin.port() + path));
        return bearer == null ? b : b.header("Authorization", "Bearer " + bearer);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return http.send(request(path, bearer).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void tapGet(String path) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + tapServer.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
