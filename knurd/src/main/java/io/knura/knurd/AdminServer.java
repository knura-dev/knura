package io.knura.knurd;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.knura.core.admin.LocalAddresses;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * knurd's admin API: a second listener, apart from the tap port, for knurctl and later
 * LoomStudio. Every request goes through the same checks, in this order:
 *
 * <ol>
 *   <li>It must come from this computer or the local network ({@link LocalAddresses}).
 *   <li>It must not have come through a proxy or tunnel: requests carrying {@code Forwarded},
 *       {@code X-Forwarded-For} or similar headers are refused, so pointing Tailscale Funnel or a
 *       reverse proxy at this port by mistake doesn't put it on the internet.
 *   <li>It must carry {@code Authorization: Bearer <token>} with a token from the {@link TokenStore}.
 *       After too many bad tokens, {@link LoginThrottle} answers an address's bad tokens with 429
 *       for a while; a good token still works.
 * </ol>
 *
 * <p>Every request, refused or not, is written to the {@link AuditLog} before it is answered; if
 * that fails the answer is a 500. The API is read-only for now and never returns key material:
 *
 * <ul>
 *   <li>{@code GET /v1/whoami}: {@code {"token":"laptop"}}, the name of the token used.
 *   <li>{@code GET /v1/status}: uptime, key mode, public URL, resolver owner, knot and tag counts.
 *   <li>{@code GET /v1/tags}: every tag seen so far and its last accepted counter.
 *   <li>{@code GET /v1/taps?limit=N}: the latest taps (default 50, at most 200), newest first.
 * </ul>
 */
final class AdminServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("knurd.admin");
    static final List<String> PROXY_HEADERS = List.of(
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP",
            "CF-Connecting-IP", "True-Client-IP", "Tailscale-User-Login", "Tailscale-Funnel-Request");

    /** What /v1/status reports. */
    record Info(String keyMode, String publicUrl, String resolverOwner, int knots, Instant startedAt) {}

    private record Answer(int status, String json, String detail) {}

    private final HttpServer server;
    private final TokenStore tokens;
    private final AuditLog audit;
    private final LoginThrottle throttle;
    private final CounterStore counters;
    private final RecentTaps taps;
    private final Info info;
    private final Clock clock;

    /** @param tls the TLS setup, or null for plain HTTP (loopback only; KnurdConfig enforces it) */
    AdminServer(String host, int port, SSLContext tls, TokenStore tokens, AuditLog audit, CounterStore counters,
            RecentTaps taps, Info info, Clock clock) throws IOException {
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (tls == null) {
            server = HttpServer.create(address, 0);
        } else {
            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters p = getSSLContext().getDefaultSSLParameters();
                    p.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                    params.setSSLParameters(p);
                }
            });
            server = https;
        }
        this.tokens = tokens;
        this.audit = audit;
        this.throttle = new LoginThrottle(clock);
        this.counters = counters;
        this.taps = taps;
        this.info = info;
        this.clock = clock;
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
        try (InputStream body = exchange.getRequestBody()) {
            body.readNBytes(64 * 1024); // Nothing takes a body yet; drain a little so keep-alive works.
        }
        InetAddress remote = exchange.getRemoteAddress().getAddress();
        String remoteText = remote.getHostAddress();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String tokenName = null;
        Answer answer;
        if (!LocalAddresses.isLocalNetwork(remote)) {
            answer = new Answer(403, error("forbidden", "the admin API only answers this computer and the local network"),
                    "not a local address");
        } else if (proxied(exchange.getRequestHeaders()) != null) {
            answer = new Answer(403, error("forbidden", "the admin API must not be reached through a proxy or tunnel"),
                    "came through a proxy (" + proxied(exchange.getRequestHeaders()) + ")");
        } else {
            Optional<String> name = tokens.nameOf(bearer(exchange.getRequestHeaders()));
            Duration wait = throttle.waitFor(remoteText);
            if (name.isPresent()) {
                // A real token always works, so someone else's bad guesses can't lock you out.
                throttle.succeeded(remoteText);
                tokenName = name.get();
                try {
                    answer = route(method, path, exchange.getRequestURI().getRawQuery(), tokenName, exchange);
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "admin request " + method + " " + path + " failed", e);
                    answer = new Answer(500, error("error", null), "internal error");
                }
            } else if (!wait.isZero()) {
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(Math.max(1, wait.toSeconds())));
                answer = new Answer(429, error("too_many_attempts", "too many bad tokens; try again later"), "locked out");
            } else {
                throttle.failed(remoteText);
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"knurd\"");
                answer = new Answer(401, error("unauthorized", "missing or unknown token"), "bad or missing token");
            }
        }
        try {
            audit.record(remoteText, tokenName, method, path, answer.status(), answer.detail());
        } catch (IOException e) {
            LOG.log(Level.ERROR, "could not write the admin audit log " + audit.file() + "; refusing the request", e);
            answer = new Answer(500, error("error", "the audit log can't be written"), null);
        }
        send(exchange, answer.status(), answer.json());
    }

    private Answer route(String method, String path, String query, String tokenName, HttpExchange exchange) {
        boolean known = switch (path) {
            case "/v1/whoami", "/v1/status", "/v1/tags", "/v1/taps" -> true;
            default -> false;
        };
        if (!known) {
            return new Answer(404, error("not_found", null), null);
        }
        if (!"GET".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET");
            return new Answer(405, error("method_not_allowed", null), null);
        }
        return switch (path) {
            case "/v1/whoami" -> ok("{\"token\":" + Json.quote(tokenName) + "}");
            case "/v1/status" -> ok(status());
            case "/v1/tags" -> ok(tags());
            default -> {
                Integer limit = limit(query);
                yield limit == null
                        ? new Answer(400, error("malformed", "limit must be a number from 1 to " + RecentTaps.CAPACITY), null)
                        : ok(taps(limit));
            }
        };
    }

    private String status() {
        long uptime = Duration.between(info.startedAt(), clock.instant()).toSeconds();
        return "{\"status\":\"up\",\"uptimeSeconds\":" + uptime + ",\"keys\":" + Json.quote(info.keyMode())
                + ",\"publicUrl\":" + Json.quote(info.publicUrl()) + ",\"resolverOwner\":" + Json.quote(info.resolverOwner())
                + ",\"knots\":" + info.knots() + ",\"tags\":" + counters.snapshot().size() + "}";
    }

    private String tags() {
        StringBuilder json = new StringBuilder("{\"tags\":[");
        String sep = "";
        for (Map.Entry<String, Integer> e : counters.snapshot().entrySet()) {
            json.append(sep).append("{\"uid\":").append(Json.quote(e.getKey()))
                    .append(",\"lastCounter\":").append(e.getValue()).append('}');
            sep = ",";
        }
        return json.append("]}").toString();
    }

    private String taps(int limit) {
        StringBuilder json = new StringBuilder("{\"taps\":[");
        String sep = "";
        for (RecentTaps.Entry e : taps.latest(limit)) {
            json.append(sep).append("{\"time\":\"").append(e.time()).append("\",\"status\":").append(Json.quote(e.status()))
                    .append(",\"uid\":").append(Json.quote(e.uid()));
            if (e.uid() != null) {
                json.append(",\"counter\":").append(e.counter());
            }
            json.append(",\"actions\":").append(e.actions()).append('}');
            sep = ",";
        }
        return json.append("]}").toString();
    }

    /** The limit query parameter, 50 when absent, or null when it is not a number from 1 to 200. */
    static Integer limit(String rawQuery) {
        Map<String, String> params = KnurdServer.parseQuery(rawQuery);
        if (params == null) {
            return null;
        }
        String value = params.get("limit");
        if (value == null) {
            return 50;
        }
        try {
            int n = Integer.parseInt(value);
            return n >= 1 && n <= RecentTaps.CAPACITY ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The first proxy header present, or null. */
    static String proxied(Headers headers) {
        for (String name : PROXY_HEADERS) {
            if (headers.containsKey(name)) {
                return name;
            }
        }
        return null;
    }

    /** The token from {@code Authorization: Bearer <token>}, or null. */
    static String bearer(Headers headers) {
        List<String> values = headers.get("Authorization");
        if (values == null || values.size() != 1) {
            return null;
        }
        String value = values.get(0).trim();
        if (value.length() < 7 || !value.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
            return null;
        }
        return value.substring(7).trim();
    }

    private static Answer ok(String json) {
        return new Answer(200, json, null);
    }

    private static String error(String status, String detail) {
        return "{\"status\":" + Json.quote(status) + (detail == null ? "" : ",\"detail\":" + Json.quote(detail)) + "}";
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
