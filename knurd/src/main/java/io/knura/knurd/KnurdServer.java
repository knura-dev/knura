package io.knura.knurd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * knurd's HTTP API, on the JDK's built-in server so there are no third-party dependencies.
 *
 * <ul>
 *   <li>{@code GET /tap?e=..&c=..} or {@code GET /tap?uid=..&ctr=..&c=..}: verify a tap. Returns
 *       JSON {@code {"status":"ok","uid":"..","counter":61}} with HTTP 200, or a failure status
 *       ({@code malformed} 400, {@code rejected} 403, {@code replay} 409).
 *   <li>{@code GET /health}: {@code {"status":"up"}}.
 * </ul>
 */
public final class KnurdServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("knurd");

    private final HttpServer server;
    private final TapVerifier verifier;

    public KnurdServer(String host, int port, TapVerifier verifier) throws IOException {
        this.verifier = verifier;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/tap", this::handleTap);
        server.createContext("/health", exchange -> {
            if (allowGet(exchange)) {
                send(exchange, 200, "{\"status\":\"up\"}");
            }
        });
    }

    public void start() {
        server.start();
    }

    /** The port actually bound, useful when started on port 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleTap(HttpExchange exchange) throws IOException {
        if (!allowGet(exchange) || !exactPath(exchange, "/tap")) {
            return;
        }
        TapResult result;
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        if (params == null) {
            result = TapResult.failed(TapResult.Status.MALFORMED, "repeated or undecodable query parameter");
        } else {
            try {
                result = verifier.verify(params);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "tap verification failed", e);
                send(exchange, 500, "{\"status\":\"error\"}");
                return;
            }
        }
        LOG.log(
                Level.INFO,
                "tap {0} uid={1} counter={2}",
                result.status().jsonName(),
                result.uid() == null ? "-" : result.uid(),
                result.counter());
        send(exchange, result.status().httpStatus(), toJson(result));
    }

    static String toJson(TapResult r) {
        StringBuilder json = new StringBuilder("{\"status\":\"").append(r.status().jsonName()).append('"');
        if (r.uid() != null) {
            json.append(",\"uid\":\"").append(r.uid()).append("\",\"counter\":").append(r.counter());
        }
        if (r.detail() != null) {
            json.append(",\"detail\":\"").append(escape(r.detail())).append('"');
        }
        return json.append('}').toString();
    }

    /** Parses a raw query string; returns null if a parameter repeats or cannot be decoded. */
    static Map<String, String> parseQuery(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (params.putIfAbsent(name, value) != null) {
                    return null;
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return params;
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean allowGet(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", "GET");
        send(exchange, 405, "{\"status\":\"method_not_allowed\"}");
        return false;
    }

    private static boolean exactPath(HttpExchange exchange, String path) throws IOException {
        if (path.equals(exchange.getRequestURI().getPath())) {
            return true;
        }
        send(exchange, 404, "{\"status\":\"not_found\"}");
        return false;
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
