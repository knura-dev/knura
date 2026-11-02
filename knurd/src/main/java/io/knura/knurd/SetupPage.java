package io.knura.knurd;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * The web pages knurd serves on the admin listener: the setup wizard at {@code /setup} and the
 * dashboard at {@code /}. Each is one HTML page, its stylesheet and its script, all inside knurd's
 * jar. Nothing is loaded from the internet, so the pages work with no connection and can't be
 * changed by anyone else's server. The files hold no settings; the pages read and change those
 * through the admin API once you sign in.
 */
final class SetupPage {
    private static final String ROOT = "/io/knura/knurd/";
    private static final Map<String, String> FILES = Map.ofEntries(
            Map.entry("/", "dashboard/index.html"),
            Map.entry("/dashboard", "dashboard/index.html"),
            Map.entry("/dashboard/", "dashboard/index.html"),
            Map.entry("/dashboard/app.css", "dashboard/app.css"),
            Map.entry("/dashboard/app.js", "dashboard/app.js"),
            Map.entry("/setup", "setup/index.html"),
            Map.entry("/setup/", "setup/index.html"),
            Map.entry("/setup/app.css", "setup/app.css"),
            Map.entry("/setup/app.js", "setup/app.js"),
            Map.entry("/setup/words.js", "setup/words.js"),
            Map.entry("/setup/icon.svg", "setup/icon.svg"));
    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';"
            + " img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none';"
            + " form-action 'none'";

    private SetupPage() {}

    static boolean serves(String method, String path) {
        return ("GET".equals(method) || "HEAD".equals(method)) && FILES.containsKey(path);
    }

    static void send(HttpExchange exchange, String path) throws IOException {
        String name = FILES.get(path);
        byte[] body;
        try (InputStream in = SetupPage.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            body = in.readAllBytes();
        }
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", type(name));
        h.set("Cache-Control", "no-cache");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", CSP);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String type(String name) {
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "text/javascript; charset=utf-8";
    }
}
