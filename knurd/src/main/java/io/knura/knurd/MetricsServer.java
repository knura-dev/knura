package io.knura.knurd;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.knura.core.admin.LocalAddresses;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * The optional {@code GET /metrics} page for Prometheus, on its own port ({@code metrics.port}),
 * off unless that is set.
 *
 * <p>It has no login, as Prometheus expects, so it follows the admin port's network rules: it only
 * listens on this computer or a local network address, answers only callers on those networks, and
 * refuses requests that came through a proxy or tunnel. Never put it on the internet. The page
 * itself holds only totals and the JVM's health (see {@link Metrics}), nothing about any tag.
 */
final class MetricsServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("knurd");
    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;
    private final Metrics metrics;

    MetricsServer(String host, int port, Metrics metrics) throws IOException {
        this.metrics = metrics;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!LocalAddresses.isLocalNetwork(exchange.getRemoteAddress().getAddress())) {
                send(exchange, 403, "only this computer and your local network may read metrics\n");
                return;
            }
            String proxy = AdminServer.proxied(exchange.getRequestHeaders());
            if (proxy != null) {
                LOG.log(Level.WARNING, "refused a metrics request that came through a proxy ({0})", proxy);
                send(exchange, 403, "metrics are never served through a proxy or tunnel\n");
                return;
            }
            if (!"/metrics".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, "not found: knurd's metrics are at /metrics\n");
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                send(exchange, 405, "method not allowed\n");
                return;
            }
            send(exchange, 200, metrics.render());
        }
    }

    private static void send(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", CONTENT_TYPE);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
