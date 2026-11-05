package io.knura.knurd;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.knura.core.admin.AdminTokens;
import io.knura.core.resolver.FlatJson;
import io.knura.core.tap.TapSignature;
import io.knura.knurgn.Tapper;
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
 *   <li>{@code POST /tap}: a tap signed by a paired phone's tap key (see {@link TapSignature}), as
 *       JSON {@code {"link":"e=..&c=..","phone":"pixel","time":1730000000,"key":"everyday",
 *       "sig":".."}}. A bad signature is refused with {@code unauthorized} 401 before the tag is
 *       even checked, so it can't use the tap up; otherwise the answer is the same as for {@code
 *       GET}, plus {@code "tapper":{"phone":..,"confirmed":..}}, and the actions learn who tapped.
 *       Only on when {@link #acceptSignedTaps} was called.
 *   <li>{@code GET /health}: {@code {"status":"up"}}.
 *   <li>{@code GET /.well-known/knura-owner}: {@code {"owner":".."}}, this knurd's owner ID at a
 *       tap resolver, so the resolver can confirm this server agreed to receive that owner's taps
 *       before it forwards any. 404 when knurd isn't registered with one.
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
    static final String OWNER_PATH = "/.well-known/knura-owner";

    private final HttpServer server;
    private final TapVerifier verifier;
    private final TapListener listener;
    private final TapThrottle throttle;
    private volatile RecentTaps recentTaps;
    private volatile Metrics metrics;
    private volatile TapKeys tapKeys;
    private volatile TapGate gate = (uid, tapper) -> true;

    /** Decides whether a genuine, new tap may run its tag's actions, given who signed it. */
    @FunctionalInterface
    interface TapGate {
        /** @param tapper the paired phone that signed the tap, or null for a plain browser tap */
        boolean allows(String uid, Tapper tapper);
    }
    private final java.time.Clock clock;

    /** Hears about taps that verified and were seen for the first time. */
    @FunctionalInterface
    public interface TapListener {
        /**
         * Must return quickly; the phone is waiting for its answer.
         *
         * @return how many actions the tap started, shown on the phone
         */
        int onVerifiedTap(String uid, int counter);

        /** Like {@link #onVerifiedTap(String, int)}, for a tap a paired phone signed. */
        default int onVerifiedTap(String uid, int counter, Tapper tapper) {
            return onVerifiedTap(uid, counter);
        }
    }

    public KnurdServer(String host, int port, TapVerifier verifier) throws IOException {
        this(host, port, verifier, (uid, counter) -> 0);
    }

    public KnurdServer(String host, int port, TapVerifier verifier, TapListener listener) throws IOException {
        this(host, port, verifier, listener, null);
    }

    /** @param resolverOwner this knurd's owner ID at a tap resolver, or null for none */
    public KnurdServer(String host, int port, TapVerifier verifier, TapListener listener, String resolverOwner)
            throws IOException {
        this(host, port, verifier, listener, resolverOwner, java.time.Clock.systemUTC());
    }

    KnurdServer(String host, int port, TapVerifier verifier, TapListener listener, String resolverOwner,
            java.time.Clock clock) throws IOException {
        this.throttle = new TapThrottle(clock);
        this.clock = clock;
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
        server.createContext(OWNER_PATH, exchange -> {
            if (!allowGet(exchange) || !exactPath(exchange, OWNER_PATH)) {
                return;
            }
            if (resolverOwner == null) {
                sendJson(exchange, 404, "{\"status\":\"not_found\"}");
            } else {
                sendJson(exchange, 200, "{\"owner\":\"" + Json.escape(resolverOwner) + "\"}");
            }
        });
    }

    /** Also keeps each tap's result in {@code taps}, for the admin API. Call before {@link #start}. */
    void recordTapsTo(RecentTaps taps) {
        this.recentTaps = taps;
    }

    /** Also counts each tap for the Prometheus page. Call before {@link #start}. */
    void countTapsIn(Metrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Checks each genuine tap against {@code gate} (who may use the tag) before its actions run. A
     * refused tap still uses its counter up, and comes back {@code refused}. Call before {@link #start}.
     */
    void gateTapsWith(TapGate gate) {
        this.gate = gate;
    }

    /** Also takes taps signed by paired phones, with their keys from {@code keys}. Call before {@link #start}. */
    void acceptSignedTaps(TapKeys keys) {
        this.tapKeys = keys;
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
        boolean signed = "POST".equals(exchange.getRequestMethod()) && tapKeys != null;
        if (!signed && !allowGet(exchange) || !exactPath(exchange, "/tap")) {
            return;
        }
        boolean html = !signed && wantsHtml(exchange);
        java.net.InetAddress remote = exchange.getRemoteAddress().getAddress();
        String address = remote.getHostAddress();
        boolean local = remote.isLoopbackAddress();
        Metrics m = metrics;
        if (!throttle.allows(address, local)) {
            if (m != null) {
                m.tap(Metrics.Extra.THROTTLED);
            }
            exchange.getResponseHeaders().set("Retry-After", Long.toString(TapThrottle.WINDOW.toSeconds()));
            sendJson(exchange, 429, "{\"status\":\"too_many_requests\"}");
            return;
        }
        long started = System.nanoTime();
        TapResult result;
        Tapper tapper = null;
        Map<String, String> params;
        if (signed) {
            Signed check = checkSignature(exchange);
            if (check.refusal() != null) {
                throttle.failed(address, local);
                if (m != null) {
                    m.tap(Metrics.Extra.BAD_SIGNATURE);
                }
                LOG.log(Level.INFO, "signed tap refused from {0}: {1}", local ? "this computer" : address, check.refusal());
                sendJson(exchange, 401, "{\"status\":\"unauthorized\",\"detail\":" + Json.quote(check.refusal()) + "}");
                return;
            }
            tapper = check.tapper();
            params = parseQuery(check.query());
        } else {
            params = parseQuery(exchange.getRequestURI().getRawQuery());
        }
        if (params == null) {
            result = TapResult.failed(TapResult.Status.MALFORMED, "repeated or undecodable query parameter");
        } else {
            try {
                Tapper who = tapper;
                result = verifier.verify(params, uid -> allowed(uid, who));
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "tap verification failed", e);
                if (m != null) {
                    m.tap(Metrics.Extra.ERROR);
                }
                if (html) {
                    sendHtml(exchange, 500, TapPage.error());
                } else {
                    sendJson(exchange, 500, "{\"status\":\"error\"}");
                }
                return;
            }
        }
        if (result.status() == TapResult.Status.REFUSED) {
            result = new TapResult(TapResult.Status.REFUSED, result.uid(), result.counter(),
                    tapper == null ? "this tag only works for paired phones" : "phone " + tapper.phone() + " may not use this tag");
        }
        long took = System.nanoTime() - started;
        if (m != null) {
            m.tap(result.status(), took);
        }
        boolean ok = result.status() == TapResult.Status.OK;
        // A refused tap is a genuine tag held by someone the tag doesn't let in, not a guessed link.
        if (!ok && result.status() != TapResult.Status.REFUSED) {
            throttle.failed(address, local);
        }
        if (ok || throttle.logFailure(n -> LOG.log(Level.WARNING, "{0} more failed taps in the last minute weren''t logged", n))) {
            // detail says why a tap failed ("MAC does not match", "unknown tag"); it never quotes the link.
            LOG.log(
                    Level.INFO,
                    "tap {0} uid={1} counter={2} from {3}{6} ({4} ms){5}",
                    result.status().jsonName(),
                    result.uid() == null ? "-" : result.uid(),
                    result.uid() == null ? "-" : Integer.toString(result.counter()),
                    local ? "this computer" : address,
                    Long.toString(took / 1_000_000),
                    result.detail() == null ? "" : ": " + result.detail(),
                    tapper == null ? "" : ", signed by phone " + tapper.phone() + (tapper.confirmed() ? " (confirmed)" : ""));
        }
        int actionsStarted = 0;
        if (result.status() == TapResult.Status.OK) {
            try {
                actionsStarted = tapper == null
                        ? listener.onVerifiedTap(result.uid(), result.counter())
                        : listener.onVerifiedTap(result.uid(), result.counter(), tapper);
                if (m != null) {
                    m.actionsStarted(actionsStarted);
                }
                LOG.log(Level.DEBUG, "tap {0}:{1} started {2} action(s)",
                        result.uid(), Integer.toString(result.counter()), actionsStarted);
            } catch (RuntimeException e) {
                // The tap itself is fine and its counter is already used; only the actions failed.
                LOG.log(Level.ERROR, "could not start actions for tap " + result.uid() + ":" + result.counter(), e);
            }
        }
        RecentTaps taps = recentTaps;
        if (taps != null) {
            taps.add(result, actionsStarted, tapper);
        }
        if (html) {
            sendHtml(exchange, result.status().httpStatus(), TapPage.render(result, actionsStarted,
                    result.status() == TapResult.Status.REFUSED ? exchange.getRequestURI().getRawQuery() : null));
        } else if (signed) {
            sendJson(exchange, result.status().httpStatus(), toJson(result, actionsStarted, tapper));
        } else {
            sendJson(exchange, result.status().httpStatus(), toJson(result));
        }
    }

    /** A broken gate keeps the tag's actions from running rather than letting anyone through. */
    private boolean allowed(String uid, Tapper tapper) {
        try {
            return gate.allows(uid, tapper);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "could not check who may use tag " + uid + "; refusing the tap", e);
            return false;
        }
    }

    /** A signed tap's link query and who signed it, or why it was refused. */
    private record Signed(String query, Tapper tapper, String refusal) {
        static Signed refused(String why) {
            return new Signed(null, null, why);
        }
    }

    private static final int MAX_SIGNED_BODY = 2048;

    /** Checks a {@code POST /tap} body's signature; the tag itself is checked after, as usual. */
    private Signed checkSignature(HttpExchange exchange) throws IOException {
        byte[] body;
        try (var in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_SIGNED_BODY + 1);
        }
        if (body.length > MAX_SIGNED_BODY) {
            return Signed.refused("the request is too big");
        }
        Map<String, Object> json;
        try {
            json = FlatJson.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Signed.refused("the request isn't JSON");
        }
        Object link = json.get("link");
        Object phone = json.get("phone");
        Object time = json.get("time");
        Object sig = json.get("sig");
        TapSignature.Kind kind = json.get("key") instanceof String k ? TapSignature.Kind.of(k) : null;
        if (!(link instanceof String query) || !TapSignature.saneQuery(query)
                || !(phone instanceof String name) || !AdminTokens.isValidName(name)
                || !(time instanceof Number seconds) || !(sig instanceof String signature) || kind == null) {
            return Signed.refused("link, phone, time, key and sig are needed");
        }
        if (!TapSignature.fresh(seconds.longValue(), clock.instant())) {
            return Signed.refused("the phone's clock is more than a minute off, or the tap is old");
        }
        var key = tapKeys.find(name, kind);
        if (key.isEmpty()) {
            return Signed.refused("this phone isn't paired here, or hasn't sent its tap keys");
        }
        if (!TapSignature.verify(key.get(), TapSignature.message(query, name, seconds.longValue(), kind), signature)) {
            return Signed.refused("the signature doesn't match");
        }
        return new Signed(query, new Tapper(name, kind == TapSignature.Kind.CONFIRM), null);
    }

    static String toJson(TapResult r, int actions, Tapper tapper) {
        String base = toJson(r);
        return base.substring(0, base.length() - 1) + ",\"actions\":" + actions + ",\"tapper\":{\"phone\":"
                + Json.quote(tapper.phone()) + ",\"confirmed\":" + tapper.confirmed() + "}}";
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
            json.append(",\"detail\":\"").append(Json.escape(r.detail())).append('"');
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
