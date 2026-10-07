package io.knura.knurd;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * knurd's HTTP API, on the JDK's built-in server so there are no third-party dependencies.
 *
 * <ul>
 *   <li>{@code GET /tap?e=..&c=..} or {@code GET /tap?uid=..&ctr=..&c=..}: verify a tap. Returns
 *       JSON {@code {"status":"ok","uid":"..","counter":61}} with HTTP 200, or a failure status
 *       ({@code malformed} 400, {@code rejected} 403, {@code replay} 409). A browser (one that
 *       asks for {@code text/html}, as a phone does when it opens a tag's link) gets a
 *       {@link TapPage} with the same HTTP status instead.
 *   <li>{@code GET /health}: {@code {"status":"up"}}.
 * </ul>
 *
 * <p>Everything here is meant to be reachable from the internet, through an HTTPS tunnel or
 * reverse proxy, so phones can open tap links. Anything for administrators belongs on a separate
 * listener that is never exposed.
 *
 * <p>Only a tap that comes back {@code ok} is passed on to the {@link TapListener} (knurgN), so
 * malformed, forged and replayed taps can never start an action.
 */
public final class KnurdServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("knurd");

    private final HttpServer server;
    private final TapVerifier verifier;
    private final TapListener listener;

    /** Hears about taps that verified and were seen for the first time. */
    @FunctionalInterface
    public interface TapListener {
        /**
         * Must return quickly; the phone is waiting for its answer.
         *
         * @return how many actions the tap started, shown on the phone
         */
        int onVerifiedTap(String uid, int counter);
    }

    public KnurdServer(String host, int port, TapVerifier verifier) throws IOException {
        this(host, port, verifier, (uid, counter) -> 0);
    }

    public KnurdServer(String host, int port, TapVerifier verifier, TapListener listener) throws IOException {
        this.verifier = verifier;
        this.listener = listener;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/tap", this::handleTap);
        server.createContext("/health", exchange -> {
            if (allowGet(exchange)) {
                sendJson(exchange, 200, "{\"status\":\"up\"}");
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
        boolean html = wantsHtml(exchange);
        TapResult result;
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        if (params == null) {
            result = TapResult.failed(TapResult.Status.MALFORMED, "repeated or undecodable query parameter");
        } else {
            try {
                result = verifier.verify(params);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "tap verification failed", e);
                if (html) {
                    sendHtml(exchange, 500, TapPage.error());
                } else {
                    sendJson(exchange, 500, "{\"status\":\"error\"}");
                }
                return;
            }
        }
        LOG.log(
                Level.INFO,
                "tap {0} uid={1} counter={2}",
                result.status().jsonName(),
                result.uid() == null ? "-" : result.uid(),
                result.counter());
        int actionsStarted = 0;
        if (result.status() == TapResult.Status.OK) {
            try {
                actionsStarted = listener.onVerifiedTap(result.uid(), result.counter());
            } catch (RuntimeException e) {
                // The tap itself is fine and its counter is already used; only the actions failed.
                LOG.log(Level.ERROR, "could not start actions for tap " + result.uid() + ":" + result.counter(), e);
            }
        }
        if (html) {
            sendHtml(exchange, result.status().httpStatus(), TapPage.render(result, actionsStarted));
        } else {
            sendJson(exchange, result.status().httpStatus(), toJson(result));
        }
    }

    /** Browsers list text/html in Accept; curl and HTTP libraries normally don't. */
    static boolean wantsHtml(HttpExchange exchange) {
        for (String accept : exchange.getRequestHeaders().getOrDefault("Accept", List.of())) {
            if (accept.contains("text/html")) {
                return true;
            }
        }
        return false;
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
        sendJson(exchange, 405, "{\"status\":\"method_not_allowed\"}");
        return false;
    }

    private static boolean exactPath(HttpExchange exchange, String path) throws IOException {
        if (path.equals(exchange.getRequestURI().getPath())) {
            return true;
        }
        sendJson(exchange, 404, "{\"status\":\"not_found\"}");
        return false;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json", json);
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        exchange.getResponseHeaders().set("Content-Security-Policy", TapPage.CONTENT_SECURITY_POLICY);
        send(exchange, status, "text/html; charset=utf-8", html);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String content)
            throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        // Tap links carry a MAC; keep them out of Referer headers and other sites' frames.
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Robots-Tag", "noindex");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
