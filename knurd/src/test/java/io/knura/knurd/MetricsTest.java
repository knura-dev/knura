package io.knura.knurd;

import static io.knura.knurd.Taps.ZERO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MetricsTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void countsTapsActionsAndShowsJvmHealth() {
        Metrics m = new Metrics();
        m.tap(TapResult.Status.OK, 3_000_000);
        m.tap(TapResult.Status.REPLAY, 20_000_000);
        m.tap(Metrics.Extra.THROTTLED);
        m.actionsStarted(2);
        m.action("delivered");
        m.action("expired");
        m.action("something-else");
        m.knots(() -> 4);
        String page = m.render();

        assertTrue(page.contains("knurd_taps_total{result=\"ok\"} 1\n"), page);
        assertTrue(page.contains("knurd_taps_total{result=\"replay\"} 1\n"));
        assertTrue(page.contains("knurd_taps_total{result=\"rejected\"} 0\n"));
        assertTrue(page.contains("knurd_taps_total{result=\"throttled\"} 1\n"));
        assertTrue(page.contains("knurd_tap_verify_seconds_bucket{le=\"0.005\"} 1\n"));
        assertTrue(page.contains("knurd_tap_verify_seconds_bucket{le=\"0.025\"} 2\n"));
        assertTrue(page.contains("knurd_tap_verify_seconds_bucket{le=\"+Inf\"} 2\n"));
        assertTrue(page.contains("knurd_tap_verify_seconds_count 2\n"));
        assertTrue(page.contains("knurd_actions_started_total 2\n"));
        assertTrue(page.contains("knurd_actions_total{outcome=\"delivered\"} 1\n"));
        assertTrue(page.contains("knurd_actions_total{outcome=\"refused\"} 0\n"));
        assertTrue(page.contains("knurd_actions_total{outcome=\"expired\"} 1\n"));
        assertTrue(page.contains("knurd_tags_with_actions 4\n"));
        assertTrue(page.contains("jvm_memory_used_bytes{area=\"heap\"} "));
        assertTrue(page.contains("jvm_threads_current "));
        assertTrue(page.contains("jvm_gc_collections_total{gc=\""));
        assertTrue(page.contains("process_uptime_seconds "));

        // Every line is a comment or "name{labels} value", as Prometheus reads it.
        Pattern sample = Pattern.compile("[a-z_]+(\\{[a-z]+=\"[^\"\\n]*\"(,[a-z]+=\"[^\"\\n]*\")*\\})? -?[0-9.E+-]+|.* [+-]?Inf");
        for (String line : page.split("\n")) {
            assertTrue(line.startsWith("# HELP ") || line.startsWith("# TYPE ") || sample.matcher(line).matches(), line);
        }
    }

    @Test
    void numbersAndLabels() {
        assertEquals("1", Metrics.number(1.0));
        assertEquals("0.005", Metrics.number(0.005));
        assertEquals("a\\\"b\\\\c\\n", Metrics.escape("a\"b\\c\n"));
    }

    @Test
    void tapServerCountsWhatItSees() throws Exception {
        Metrics m = new Metrics();
        TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), new MemoryCounterStore());
        KnurdServer taps = new KnurdServer("127.0.0.1", 0, verifier, (uid, counter) -> 3);
        taps.countTapsIn(m);
        taps.start();
        try {
            String path = "/tap?" + Taps.query(Taps.AN12196);
            get(taps.port(), path);
            get(taps.port(), path);
            get(taps.port(), "/tap");
        } finally {
            taps.close();
        }
        String page = m.render();
        assertTrue(page.contains("knurd_taps_total{result=\"ok\"} 1\n"), page);
        assertTrue(page.contains("knurd_taps_total{result=\"replay\"} 1\n"));
        assertTrue(page.contains("knurd_taps_total{result=\"malformed\"} 1\n"));
        assertTrue(page.contains("knurd_actions_started_total 3\n"));
        assertFalse(page.contains("04DE5F1EACC040"), "no tag UIDs on the page");
    }

    @Test
    void servesMetricsOnlyAtItsPath() throws Exception {
        MetricsServer server = new MetricsServer("127.0.0.1", 0, new Metrics());
        server.start();
        try {
            HttpResponse<String> ok = get(server.port(), "/metrics");
            assertEquals(200, ok.statusCode());
            assertEquals(MetricsServer.CONTENT_TYPE, ok.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(ok.body().contains("# TYPE knurd_taps_total counter"));
            assertEquals(404, get(server.port(), "/").statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> proxied = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                    .header("X-Forwarded-For", "203.0.113.9").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, proxied.statusCode());
        } finally {
            server.close();
        }
    }

    @Test
    void configIsOffUnlessAPortIsSet() {
        assertNull(KnurdConfig.metrics(new Properties(), 8424, null));
        assertEquals(new KnurdConfig.MetricsListener("127.0.0.1", 9425),
                KnurdConfig.metrics(props("metrics.port", "9425"), 8424, null));
        assertEquals(new KnurdConfig.MetricsListener("192.168.1.20", 9425),
                KnurdConfig.metrics(props("metrics.port", "9425", "metrics.listen.host", "192.168.1.20"), 8424, null));
        assertEquals("0.0.0.0", KnurdConfig.metrics(props("metrics.port", "9425", "metrics.listen.host", "0.0.0.0"),
                8424, null).host());
    }

    @Test
    void configRefusesPublicHostsAndSharedPorts() {
        assertTrue(error(props("metrics.port", "9425", "metrics.listen.host", "8.8.8.8"), null)
                .contains("public internet address"));
        assertTrue(error(props("metrics.port", "9425", "metrics.listen.host", "metrics.example"), null)
                .contains("must be an IP address"));
        assertTrue(error(props("metrics.port", "8424"), null).contains("must differ"));
        KnurdConfig.Admin admin = new KnurdConfig.Admin("127.0.0.1", 8425, true, null, null, null,
                Path.of("audit.log"), java.util.Set.of(), 8426);
        assertTrue(error(props("metrics.port", "8425"), admin).contains("must differ"));
        assertTrue(error(props("metrics.port", "8426"), admin).contains("must differ"));
        assertTrue(error(props("metrics.port", "abc"), null).contains("must be a number"));
        assertTrue(error(props("metrics.port", "70000"), null).contains("1 to 65535"));
        assertTrue(error(props("metrics.listen.host", "127.0.0.1"), null).contains("needs metrics.port"));
    }

    private static String error(Properties p, KnurdConfig.Admin admin) {
        return assertThrows(IllegalArgumentException.class, () -> KnurdConfig.metrics(p, 8424, admin)).getMessage();
    }

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
