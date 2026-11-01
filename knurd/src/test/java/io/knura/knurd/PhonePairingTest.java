package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.CertFingerprint;
import io.knura.core.admin.PrivateFiles;
import io.knura.core.crypto.Hex;
import io.knura.core.resolver.FlatJson;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pairing a phone from the setup page: certificate, Wi-Fi listener, pairing code, all without a restart. */
class PhonePairingTest {
    private static final String MASTER = "00112233445566778899AABBCCDDEEFF";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final AdminServerTest.MutableClock clock = new AdminServerTest.MutableClock(Instant.parse("2026-11-01T00:00:00Z"));
    private final SecureRandom random = new SecureRandom();
    private Path knurdFile;
    private AuditLog audit;
    private AdminServer admin;
    private LiveActions live;
    private int lanPort;
    private String cookie;

    @BeforeEach
    void start() throws Exception {
        knurdFile = dir.resolve("knurd.properties");
        PrivateFiles.create(knurdFile, "master.key=" + MASTER + "\npublic.url=https://knura-home.tail8c2f4.ts.net\n"
                + "admin.tokens.file=tokens.properties\n");
        Path tokensFile = dir.resolve("tokens.properties");
        PrivateFiles.create(tokensFile, AdminTokens.format(Map.of("laptop", AdminTokens.hash(AdminTokens.generate(random)))));
        try (ServerSocket s = new ServerSocket(0)) {
            lanPort = s.getLocalPort();
        }
        audit = new AuditLog(dir.resolve("audit.log"), clock);
        live = new LiveActions(null, Clock.systemUTC());
        HomeSettings settings = new HomeSettings(knurdFile, null, live, Clock.systemUTC());
        KnurdConfig.Admin a = new KnurdConfig.Admin("127.0.0.1", 0, false, tokensFile, null, null, dir.resolve("audit.log"),
                Set.of(), lanPort);
        TokenStore tokens = new TokenStore(tokensFile);
        TagKeys keys = TagKeys.derived(Hex.decode(MASTER), Map.of());
        admin = new AdminServer("127.0.0.1", 0, null, tokens, audit, new MemoryCounterStore(), new RecentTaps(clock),
                new AdminServer.Info("master", null, null, 0, clock.instant()), clock, null);
        PhonePairing phones = new PhonePairing(keys, null, a, tokens, settings, null, clock,
                () -> List.of("127.0.0.1", "10.0.0.7"));
        phones.attach(admin);
        admin.manage(new AdminServer.Manage(new AdminPassword(dir.resolve(AdminPassword.FILE), random, 1000),
                new Sessions(clock), settings, new Tailscale(null, 0), phones));
        admin.start();
        HttpResponse<String> set = send("POST", "/v1/password", "{\"password\":\"lantern-orbit-42\"}");
        cookie = Sessions.fromCookie(set.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0]);
    }

    @AfterEach
    void stop() throws IOException {
        admin.close();
        audit.close();
        live.close();
    }

    @Test
    void pairsAPhoneWithoutARestart() throws Exception {
        assertEquals("[]", field(send("GET", "/v1/settings", null).body(), "phones"));
        HttpResponse<String> r = send("POST", "/v1/phones/pair", "{\"name\":\"pixel-9\"}");
        assertEquals(200, r.statusCode(), r.body());
        String body = r.body();

        // A certificate was made, owner-only, and everything is saved for the next start.
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("knurd-admin.p12"))));
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("knurd-admin.p12.password"))));
        Properties saved = new Properties();
        try (var in = Files.newBufferedReader(knurdFile)) {
            saved.load(in);
        }
        assertEquals("knurd-admin.p12", saved.getProperty("admin.tls.keystore"));
        assertEquals(String.valueOf(lanPort), saved.getProperty("admin.lan.port"));
        assertEquals("pixel-9", saved.getProperty("admin.provision.tokens"));
        KnurdConfig.Admin next = KnurdConfig.load(knurdFile).admin();
        assertEquals(lanPort, next.lanPort());
        assertFalse(next.tls(), "this computer keeps plain HTTP on loopback; only the Wi-Fi listener uses TLS");

        // The QR code holds the link; the link holds the Wi-Fi address, the certificate and a one-time code.
        assertTrue(body.contains("\"qr\":[\""), body);
        String link = (String) FlatJson.parse(body.replaceAll(",\"qr\":\\[[^\\]]*\\]", "")
                .replaceAll(",\"addresses\":\\[[^\\]]*\\]", "")).get("link");
        assertTrue(link.startsWith("knura://pair?url=https%3A%2F%2F127.0.0.1%3A" + lanPort + "&fp="), link);
        Map<String, String> q = query(link);
        assertEquals("https://127.0.0.1:" + lanPort, q.get("url"));
        assertEquals("[\"127.0.0.1\",\"10.0.0.7\"]", field(body, "addresses"));
        assertEquals(1, Files.readString(dir.resolve(AdminTokens.PAIRING_FILE)).lines().filter(l -> l.startsWith("pixel-9=")).count());

        // Not paired until the app swaps the code, over the Wi-Fi listener, pinned to that certificate.
        assertEquals("[]", field(send("GET", "/v1/settings", null).body(), "phones"));
        String[] answer = wifi("POST", "/v1/pair", "{\"code\":\"" + q.get("code") + "\"}", null, q.get("fp"));
        assertEquals("200", answer[0], answer[1]);
        String token = (String) FlatJson.parse(answer[1]).get("token");
        assertEquals("[\"pixel-9\"]", field(send("GET", "/v1/settings", null).body(), "phones"));

        // The new phone may set tags up and manage this Knura straight away: no restart.
        assertEquals("200", wifi("GET", "/v1/settings", null, token, q.get("fp"))[0]);
        assertEquals("409", wifi("POST", "/v1/phones/pair", "{\"name\":\"pixel-9\"}", token, q.get("fp"))[0],
                "that name is taken now");
    }

    @Test
    void anotherAddressAndASecondPhoneReuseTheListener() throws Exception {
        String first = send("POST", "/v1/phones/pair", "{\"name\":\"pixel-9\"}").body();
        HttpResponse<String> r = send("POST", "/v1/phones/pair", "{\"name\":\"fold7\",\"address\":\"10.0.0.7\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"url\":\"https://10.0.0.7:" + lanPort + "\""), r.body());
        assertEquals(field(first, "fingerprint"), field(r.body(), "fingerprint"), "same certificate");
        assertEquals(400, send("POST", "/v1/phones/pair", "{\"name\":\"tab\",\"address\":\"8.8.8.8\"}").statusCode(),
                "only this computer's own addresses");
        assertEquals(400, send("POST", "/v1/phones/pair", "{\"name\":\"My Phone\"}").statusCode());
    }

    @Test
    void needsAnAddressFirst() throws Exception {
        Files.writeString(knurdFile, "master.key=" + MASTER + "\nadmin.tokens.file=tokens.properties\n");
        HttpResponse<String> r = send("POST", "/v1/phones/pair", "{\"name\":\"pixel-9\"}");
        assertEquals(409, r.statusCode());
        assertTrue(r.body().contains("no_address"), r.body());
        assertFalse(Files.exists(dir.resolve("knurd-admin.p12")), "nothing made before it can work");
    }

    @Test
    void notFromAnotherSite() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin.port() + "/v1/phones/pair"))
                .header("Cookie", Sessions.COOKIE + "=" + cookie)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"pixel-9\"}")).build();
        assertEquals(403, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(props(
                "master.key=" + MASTER + "\nadmin.tokens.file=t\nadmin.lan.port=8426\n"), dir), "needs TLS");
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(props(
                "master.key=" + MASTER + "\nadmin.security=plaintext\nadmin.lan.port=8426\n"), dir));
        assertThrows(IllegalArgumentException.class, () -> KnurdConfig.fromProperties(props(
                "master.key=" + MASTER + "\nadmin.tokens.file=t\nadmin.lan.port=8424\nadmin.tls.keystore=k\n"
                        + "admin.tls.password.file=p\n"), dir), "not the tap port");
    }

    private static Properties props(String text) throws IOException {
        Properties p = new Properties();
        p.load(new java.io.StringReader(text));
        return p;
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin.port() + path))
                .header(AdminServer.REQUEST_HEADER, "1").header("Cookie", Sessions.COOKIE + "=" + cookie)
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (cookie == null) {
            b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin.port() + path))
                    .header(AdminServer.REQUEST_HEADER, "1")
                    .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A request to the Wi-Fi listener as the app makes it: trusting only the certificate with this fingerprint. */
    private String[] wifi(String method, String path, String json, String token, String fingerprint) throws Exception {
        X509TrustManager pinned = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                if (!CertFingerprint.of(chain[0]).equalsIgnoreCase(fingerprint)) {
                    throw new java.security.cert.CertificateException("not the pinned certificate");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {pinned}, null);
        HttpsURLConnection c = (HttpsURLConnection) URI.create("https://127.0.0.1:" + lanPort + path).toURL()
                .openConnection(java.net.Proxy.NO_PROXY);
        c.setSSLSocketFactory(tls.getSocketFactory());
        // An IP address has no name to check: the certificate's fingerprint is the check, as in the app.
        c.setHostnameVerifier((host, session) -> {
            try {
                return session.getPeerCertificates()[0] instanceof X509Certificate x
                        && CertFingerprint.of(x).equalsIgnoreCase(fingerprint);
            } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
                return false;
            }
        });
        c.setRequestMethod(method);
        c.setRequestProperty(AdminServer.REQUEST_HEADER, "1");
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        var in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new String[] {String.valueOf(status), body};
    }

    private static Map<String, String> query(String link) {
        Map<String, String> q = new java.util.HashMap<>();
        for (String part : link.substring(link.indexOf('?') + 1).split("&")) {
            int eq = part.indexOf('=');
            q.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        assertNotNull(q.get("code"));
        return q;
    }

    /** The raw JSON of one top-level field holding a string, list or other value. */
    private static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":");
        assertTrue(i >= 0, name + " in " + json);
        int start = i + name.length() + 3;
        int depth = 0;
        boolean str = false;
        for (int j = start; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (str) {
                if (ch == '\\') {
                    j++;
                } else if (ch == '"') {
                    str = false;
                    if (depth == 0) {
                        return json.substring(start, j + 1);
                    }
                }
            } else if (ch == '"') {
                str = true;
            } else if (ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ']' || ch == '}') {
                if (depth == 0) {
                    return json.substring(start, j);
                }
                depth--;
                if (depth == 0) {
                    return json.substring(start, j + 1);
                }
            } else if (ch == ',' && depth == 0) {
                return json.substring(start, j);
            }
        }
        return json.substring(start);
    }
}
