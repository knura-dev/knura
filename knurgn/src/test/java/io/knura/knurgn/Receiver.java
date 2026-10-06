package io.knura.knurgn;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/** A local webhook target for tests. {@code statusForCall} maps the call number (1, 2...) to a status. */
public final class Receiver implements AutoCloseable {
    public record Request(String method, Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final AtomicInteger calls = new AtomicInteger();

    public Receiver(IntUnaryOperator statusForCall) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var h = exchange.getRequestHeaders();
            requests.add(new Request(exchange.getRequestMethod(), Map.of(
                    "content-type", String.valueOf(h.getFirst("Content-Type")),
                    "webhook-id", String.valueOf(h.getFirst("webhook-id")),
                    "webhook-timestamp", String.valueOf(h.getFirst("webhook-timestamp")),
                    "webhook-signature", String.valueOf(h.getFirst("webhook-signature"))), body));
            int status = statusForCall.applyAsInt(calls.incrementAndGet());
            if (status / 100 == 3) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/elsewhere");
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    }

    /** The next request, or null if none arrives within the timeout. */
    public Request next(long millis) throws InterruptedException {
        return requests.poll(millis, TimeUnit.MILLISECONDS);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
