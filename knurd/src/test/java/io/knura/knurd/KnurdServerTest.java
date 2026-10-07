package io.knura.knurd;

import static io.knura.knurd.Taps.ZERO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnurdServerTest {
    private final HttpClient http = HttpClient.newHttpClient();
    private KnurdServer server;

    @BeforeEach
    void start() throws Exception {
        TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), new MemoryCounterStore());
        server = new KnurdServer("127.0.0.1", 0, verifier);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void tapThenReplay() throws Exception {
        String path = "/tap?" + Taps.query(Taps.AN12196);
        HttpResponse<String> first = get(path);
        assertEquals(200, first.statusCode());
        assertEquals("{\"status\":\"ok\",\"uid\":\"04DE5F1EACC040\",\"counter\":61}", first.body());
        assertEquals("application/json", first.headers().firstValue("Content-Type").orElseThrow());

        HttpResponse<String> second = get(path);
        assertEquals(409, second.statusCode());
        assertEquals(
                "{\"status\":\"replay\",\"uid\":\"04DE5F1EACC040\",\"counter\":61,\"detail\":\"counter already used\"}",
                second.body());
    }

    @Test
    void errorStatuses() throws Exception {
        assertEquals(400, get("/tap").statusCode());
        assertEquals(400, get("/tap?c=1&c=2").statusCode());
        assertEquals(403, get("/tap?e=EF963FF7828658A599F3041510671E88&c=0000000000000000").statusCode());
        assertEquals(404, get("/tap/extra").statusCode());
        HttpResponse<String> post = http.send(
                HttpRequest.newBuilder(uri("/tap")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }

    @Test
    void browsersGetATapPage() throws Exception {
        String path = "/tap?" + Taps.query(Taps.AN12196);
        HttpResponse<String> first = getHtml(path);
        assertEquals(200, first.statusCode());
        assertEquals("text/html; charset=utf-8", first.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(first.body().contains("Tap verified"), first.body());
        assertTrue(first.body().contains("Tag 04DE5F1EACC040 &middot; tap #61"), first.body());
        assertTrue(first.body().contains("No action is set up"), first.body());

        HttpResponse<String> again = getHtml(path);
        assertEquals(409, again.statusCode());
        assertTrue(again.body().contains("Link already used"), again.body());

        HttpResponse<String> forged = getHtml("/tap?e=EF963FF7828658A599F3041510671E88&c=0000000000000000");
        assertEquals(403, forged.statusCode());
        assertTrue(forged.body().contains("Not verified"), forged.body());
        assertFalse(forged.body().contains("04DE5F1EACC040"), "an unverified tap must not show a UID");
        assertFalse(forged.body().contains("MAC"), "the page must not say why a tap failed");
    }

    @Test
    void tapPageCountsActions() throws Exception {
        server.close();
        TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), new MemoryCounterStore());
        server = new KnurdServer("127.0.0.1", 0, verifier, (uid, counter) -> 2);
        server.start();
        assertTrue(getHtml("/tap?" + Taps.query(Taps.AN12196)).body().contains("Running its 2 actions now."));
    }

    @Test
    void tapPageAllowsOnlyItsOwnStyle() throws Exception {
        HttpResponse<String> page = getHtml("/tap?" + Taps.query(Taps.AN12196));
        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.startsWith("default-src 'none'; style-src 'sha256-"), csp);
        String body = page.body();
        String style = body.substring(body.indexOf("<style>") + 7, body.indexOf("</style>"));
        String hash = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(style.getBytes(StandardCharsets.UTF_8)));
        assertTrue(csp.contains("'sha256-" + hash + "'"), "CSP hash must match the page's style");
        assertFalse(body.contains("<script"), body);
    }

    @Test
    void everyResponseHasSafetyHeaders() throws Exception {
        for (HttpResponse<String> response : List.of(get("/health"), get("/tap"), getHtml("/tap"))) {
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
            assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    @Test
    void health() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"up\"}", response.body());
    }

    @Test
    void parsesQuery() {
        assertEquals(Map.of("e", "AB", "c", "C D"), KnurdServer.parseQuery("e=AB&c=C+D"));
        assertEquals(Map.of(), KnurdServer.parseQuery(null));
        assertNull(KnurdServer.parseQuery("c=1&c=2"));
        assertNull(KnurdServer.parseQuery("c=%zz"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Asks the way a phone's browser does. */
    private HttpResponse<String> getHtml(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
