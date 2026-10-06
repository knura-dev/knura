// A small webhook receiver for trying out knurgN. It checks each webhook's signature and prints it.
//
//   export KNURA_WEBHOOK_SECRET='whsec_...'   # the same secret knurgN signs with
//   java examples/webhook-receiver/WebhookReceiver.java [port]   # default port 8425
//
// It listens on 127.0.0.1 only. Use it as a starting point for your own receiver: check the
// signature, refuse old timestamps and ignore ids you have already seen, as below.
// Single file, JDK 21, no dependencies.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class WebhookReceiver {
    /** Webhooks whose timestamp is further than this from our clock are refused. */
    static final long TOLERANCE_SECONDS = 5 * 60;

    static byte[] key;
    static final Set<String> seenIds = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) throws Exception {
        String secret = System.getenv("KNURA_WEBHOOK_SECRET");
        if (secret == null || !secret.startsWith("whsec_")) {
            System.err.println("Set KNURA_WEBHOOK_SECRET to the webhook secret (it starts with whsec_).");
            System.exit(1);
        }
        key = Base64.getDecoder().decode(secret.substring("whsec_".length()).trim());
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8425;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", WebhookReceiver::handle);
        server.start();
        System.out.println("Waiting for knurgN webhooks on http://127.0.0.1:" + port + "/ (Ctrl+C to stop)");
    }

    static void handle(HttpExchange exchange) throws IOException {
        byte[] bodyBytes;
        try (InputStream in = exchange.getRequestBody()) {
            bodyBytes = in.readAllBytes();
        }
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String id = exchange.getRequestHeaders().getFirst("webhook-id");
        String timestamp = exchange.getRequestHeaders().getFirst("webhook-timestamp");
        String signatures = exchange.getRequestHeaders().getFirst("webhook-signature");

        if (!"POST".equals(exchange.getRequestMethod()) || id == null || timestamp == null || signatures == null) {
            System.out.println("REFUSED  not a knurgN webhook");
            respond(exchange, 400);
            return;
        }
        if (!signatureMatches(id, timestamp, body, signatures)) {
            System.out.println("REFUSED  " + id + ": bad signature (wrong secret, or the message was changed)");
            respond(exchange, 401);
            return;
        }
        long age;
        try {
            age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            age = Long.MAX_VALUE;
        }
        if (age > TOLERANCE_SECONDS) {
            System.out.println("REFUSED  " + id + ": timestamp is too old or in the future");
            respond(exchange, 400);
            return;
        }
        if (!seenIds.add(id)) {
            // Already handled. Answer 200 so the sender stops retrying, but do nothing again.
            System.out.println("IGNORED  " + id + ": already received (a retry)");
            respond(exchange, 200);
            return;
        }
        System.out.println("RECEIVED " + id + ": signature OK");
        System.out.println("         " + body);
        respond(exchange, 204);
    }

    /** Standard Webhooks: v1 = base64(HMAC-SHA256(key, id.timestamp.body)); the header may hold several. */
    static boolean signatureMatches(String id, String timestamp, String body, String header) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] expected = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            for (String candidate : header.split(" ")) {
                if (!candidate.startsWith("v1,")) {
                    continue;
                }
                byte[] given;
                try {
                    given = Base64.getDecoder().decode(candidate.substring(3));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (MessageDigest.isEqual(expected, given)) { // constant time
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
