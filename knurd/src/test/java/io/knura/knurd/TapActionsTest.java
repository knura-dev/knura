package io.knura.knurd;

import static io.knura.knurd.Taps.ZERO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.knura.knurgn.Knurgn;
import io.knura.knurgn.KnurgnConfig;
import io.knura.knurgn.WebhookSignature;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A tap through knurd reaches knurgN, and only a verified, first-time tap does. */
class TapActionsTest {
    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void onlyOkTapsReachTheListener() throws Exception {
        List<String> heard = new CopyOnWriteArrayList<>();
        TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), new MemoryCounterStore());
        try (KnurdServer server = new KnurdServer("127.0.0.1", 0, verifier, (uid, ctr) -> heard.add(uid + ":" + ctr))) {
            server.start();
            get(server, "/tap?" + Taps.query(Taps.AN12196)); // ok
            get(server, "/tap?" + Taps.query(Taps.AN12196)); // replay
            get(server, "/tap?e=EF963FF7828658A599F3041510671E88&c=0000000000000000"); // forged
            get(server, "/tap?c=1"); // malformed
            // Extra parameters can't steer anything: the listener only ever gets the UID and counter.
            get(server, "/tap?" + Taps.query(Taps.plain("04DE5F1EACC040", 62, ZERO_KEY)) + "&action=unlock");
        }
        assertEquals(List.of("04DE5F1EACC040:61", "04DE5F1EACC040:62"), heard);
    }

    @Test
    void verifiedTapSendsSignedWebhook() throws Exception {
        BlockingQueue<String[]> received = new LinkedBlockingQueue<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                var h = exchange.getRequestHeaders();
                received.add(new String[] {
                    h.getFirst("webhook-id"), h.getFirst("webhook-timestamp"), h.getFirst("webhook-signature"), body
                });
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();

        Path rules = dir.resolve("knurgn.properties");
        Files.writeString(rules, "knot.04DE5F1EACC040.name=desk-candle\n"
                + "knot.04DE5F1EACC040.actions=desk\n"
                + "webhook.desk.url=http://127.0.0.1:" + receiver.getAddress().getPort() + "/knura\n");
        Path secrets = dir.resolve("knurgn-secrets.properties");
        Files.writeString(secrets, "desk=" + SECRET + "\n");
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"));

        TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), new MemoryCounterStore());
        try (Knurgn knurgn = KnurgnConfig.load(rules, Clock.systemUTC());
                KnurdServer server = new KnurdServer("127.0.0.1", 0, verifier, knurgn::onVerifiedTap)) {
            server.start();
            assertEquals(200, get(server, "/tap?" + Taps.query(Taps.AN12196)).statusCode());
            String[] hook = received.poll(5, TimeUnit.SECONDS);
            assertEquals("04DE5F1EACC040:61", hook[0]);
            assertTrue(hook[3].contains("\"knot\":\"desk-candle\""), hook[3]);
            assertEquals(
                    WebhookSignature.sign(WebhookSignature.parseSecret("desk", SECRET), hook[0], Long.parseLong(hook[1]), hook[3]),
                    hook[2]);

            assertEquals(409, get(server, "/tap?" + Taps.query(Taps.AN12196)).statusCode());
            assertNull(received.poll(300, TimeUnit.MILLISECONDS), "a replayed tap must not send a webhook");
        } finally {
            receiver.stop(0);
        }
    }

    private HttpResponse<String> get(KnurdServer server, String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.port() + path);
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
