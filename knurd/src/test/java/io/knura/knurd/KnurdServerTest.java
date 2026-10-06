package io.knura.knurd;

import static io.knura.knurd.Taps.ZERO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
