package io.knura.knurd;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.LocalAddresses;
import io.knura.core.resolver.FlatJson;
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
 * <p>In plaintext mode ({@code admin.security=plaintext}, no {@link TokenStore}) step 4 is
 * skipped: anyone on this computer may use the API, as with Kafka's PLAINTEXT listener. knurd
 * then listens on loopback only, and also requires the Host header to name this computer, so a
 * web page can't reach the API by pointing its own domain name at 127.0.0.1 (DNS rebinding).
 *
 * <p>Every request, refused or not, is written to the {@link AuditLog} before it is answered; if
 * that fails the answer is a 500. The API never returns key material. Apart from setting tags up
 * through a phone and removing tokens, it only reads:
 *
 * <ul>
 *   <li>{@code GET /v1/whoami}: {@code {"token":"laptop"}}, the name of the token used.
 *   <li>{@code GET /v1/status}: uptime, key mode, public URL, resolver owner, knot and tag counts.
 *   <li>{@code GET /v1/tags}: every tag seen so far and its last accepted counter.
 *   <li>{@code GET /v1/taps?limit=N}: the latest taps (default 50, at most 200), newest first.
 *   <li>{@code POST /v1/nfc/sessions} and {@code POST /v1/nfc/sessions/<id>}: set a tag up,
 *       reset or read it through a phone ({@link NfcRelay}). Only for the tokens named in {@code
 *       admin.provision.tokens}, and off when that is empty.
 *   <li>{@code GET /v1/tokens}: {@code {"you":"fold7","tokens":["fold7","laptop"]}}, the token names.
 *   <li>{@code DELETE /v1/tokens/<name>}: removes a token, as {@code knurctl admin token remove}
 *       does, so a phone can lock out a lost laptop. Only for the tokens in {@code
 *       admin.provision.tokens}.
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

    private static final String NFC_SESSIONS = "/v1/nfc/sessions";
    private static final int MAX_NFC_BODY = 4096;
    private static final String TOKENS = "/v1/tokens";

    private final HttpServer server;
    private final TokenStore tokens;
    private final AuditLog audit;
    private final LoginThrottle throttle;
    private final CounterStore counters;
    private final RecentTaps taps;
    private final Info info;
    private final Clock clock;
    private final NfcRelay nfc;

    AdminServer(String host, int port, SSLContext tls, TokenStore tokens, AuditLog audit, CounterStore counters,
            RecentTaps taps, Info info, Clock clock) throws IOException {
        this(host, port, tls, tokens, audit, counters, taps, info, clock, null);
    }

    /**
     * @param tls the TLS setup, or null for plain HTTP (loopback only; KnurdConfig enforces it)
     * @param tokens the tokens to accept, or null for plaintext mode: no login (loopback only)
     * @param nfc setting tags up through a phone, or null when that is off
     */
    AdminServer(String host, int port, SSLContext tls, TokenStore tokens, AuditLog audit, CounterStore counters,
            RecentTaps taps, Info info, Clock clock, NfcRelay nfc) throws IOException {
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
        this.nfc = nfc;
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
        byte[] requestBody;
        try (InputStream body = exchange.getRequestBody()) {
            // Only the phone relay reads a body, and a small one; reading a little more than it
            // allows lets keep-alive work and too large a body be refused.
            requestBody = body.readNBytes(64 * 1024);
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
        } else if (tokens == null && !loopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
            answer = new Answer(403, error("forbidden", "in plaintext mode the admin API only answers requests for"
                    + " 127.0.0.1 or localhost"), "Host is not this computer");
        } else if (tokens == null) {
            try {
                answer = route(method, path, exchange.getRequestURI().getRawQuery(), null, requestBody, exchange);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "admin request " + method + " " + path + " failed", e);
                answer = new Answer(500, error("error", null), "internal error");
            }
            if (answer.detail() == null) {
                answer = new Answer(answer.status(), answer.json(), "plaintext: no login");
            }
        } else {
            Optional<String> name = tokens.nameOf(bearer(exchange.getRequestHeaders()));
            Duration wait = throttle.waitFor(remoteText);
            if (name.isPresent()) {
                // A real token always works, so someone else's bad guesses can't lock you out.
                throttle.succeeded(remoteText);
                tokenName = name.get();
                try {
                    answer = route(method, path, exchange.getRequestURI().getRawQuery(), tokenName, requestBody, exchange);
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

    private Answer route(String method, String path, String query, String tokenName, byte[] body,
            HttpExchange exchange) {
        if (path.equals(NFC_SESSIONS) || path.startsWith(NFC_SESSIONS + "/")) {
            return nfc(method, path, tokenName, body, exchange);
        }
        if (path.startsWith(TOKENS + "/")) {
            return removeToken(method, path.substring(TOKENS.length() + 1), tokenName, exchange);
        }
        boolean known = switch (path) {
            case "/v1/whoami", "/v1/status", "/v1/tags", "/v1/taps", TOKENS -> true;
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
            case "/v1/whoami" -> ok("{\"token\":" + Json.quote(tokenName) + ",\"security\":"
                    + (tokens == null ? "\"plaintext\"" : "\"token\"") + "}");
            case "/v1/status" -> ok(status());
            case "/v1/tags" -> ok(tags());
            case TOKENS -> tokens == null
                    ? new Answer(404, error("not_found", "plaintext mode has no tokens"), null)
                    : ok(tokenList(tokenName));
            default -> {
                Integer limit = limit(query);
                yield limit == null
                        ? new Answer(400, error("malformed", "limit must be a number from 1 to " + RecentTaps.CAPACITY), null)
                        : ok(taps(limit));
            }
        };
    }

    private Answer nfc(String method, String path, String tokenName, byte[] body, HttpExchange exchange) {
        String id = path.equals(NFC_SESSIONS) ? null : path.substring(NFC_SESSIONS.length() + 1);
        if (id != null && !NfcRelay.isSessionId(id)) {
            return new Answer(404, error("not_found", null), null);
        }
        if (!"POST".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "POST");
            return new Answer(405, error("method_not_allowed", null), null);
        }
        if (nfc == null) {
            return new Answer(403, error("forbidden", "setting tags up through a phone is off; turn it on with"
                    + " admin.provision.tokens in knurd.properties"), "nfc off");
        }
        if (!nfc.allows(tokenName)) {
            return new Answer(403, error("forbidden", "this token may not set tags up; add it to"
                    + " admin.provision.tokens in knurd.properties"), "token may not use nfc");
        }
        if (body.length > MAX_NFC_BODY) {
            return new Answer(413, error("too_large", "request body over " + MAX_NFC_BODY + " bytes"), null);
        }
        Map<String, Object> fields;
        try {
            fields = FlatJson.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return new Answer(400, error("malformed", "the body must be a flat JSON object: " + e.getMessage()), null);
        }
        NfcRelay.Reply reply = id == null ? nfc.start(tokenName, fields) : nfc.step(tokenName, id, fields);
        return new Answer(reply.status(), reply.json(), reply.detail());
    }

    private String tokenList(String you) {
        StringBuilder json = new StringBuilder("{\"you\":").append(Json.quote(you)).append(",\"tokens\":[");
        String sep = "";
        for (String name : tokens.names()) {
            json.append(sep).append(Json.quote(name));
            sep = ",";
        }
        return json.append("]}").toString();
    }

    /** {@code DELETE /v1/tokens/<name>}: only tokens that may set tags up may remove tokens. */
    private Answer removeToken(String method, String name, String tokenName, HttpExchange exchange) {
        if (tokens == null || !AdminTokens.isValidName(name)) {
            return new Answer(404, error("not_found", null), null);
        }
        if (!"DELETE".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "DELETE");
            return new Answer(405, error("method_not_allowed", null), null);
        }
        if (nfc == null || !nfc.allows(tokenName)) {
            return new Answer(403, error("forbidden", "only tokens in admin.provision.tokens may remove tokens"),
                    "token may not remove tokens");
        }
        try {
            if (!tokens.remove(name)) {
                return new Answer(404, error("not_found", "no token named " + name), "no token " + name);
            }
        } catch (IOException e) {
            LOG.log(Level.ERROR, "could not remove admin token " + name, e);
            return new Answer(500, error("error", "couldn't change the tokens file: " + e.getMessage()),
                    "remove token " + name + " failed");
        }
        return new Answer(200, "{\"removed\":" + Json.quote(name) + "}", "removed token " + name);
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

    /** Whether a Host header (with or without a port) names this computer. */
    static boolean loopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end < 0 ? h : h.substring(0, end + 1);
        } else if (h.indexOf(':') >= 0 && h.indexOf(':') == h.lastIndexOf(':')) {
            h = h.substring(0, h.indexOf(':'));
        }
        return LocalAddresses.isLoopbackName(h);
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
