package io.knura.knurd;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.LocalAddresses;
import io.knura.core.backup.BackupRecord;
import io.knura.core.resolver.FlatJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
 *   <li>{@code POST /v1/pair} with {@code {"code":"knurp_..."}}, the only request without a token:
 *       swaps a one-time pairing code from {@code knurctl admin pair-phone} for the phone's token
 *       ({@link TokenStore#pair}). Wrong codes count as bad tokens for {@link LoginThrottle}.
 *   <li>{@code GET /v1/whoami}: {@code {"token":"laptop"}}, the name of the token used.
 *   <li>{@code GET /v1/status}: uptime, key mode, public URL, resolver owner, knot and tag counts.
 *   <li>{@code GET /v1/tags}: every tag seen so far and its last accepted counter.
 *   <li>{@code GET /v1/taps?limit=N}: the latest taps (default 50, at most 1000), newest first,
 *       optionally only one tag's ({@code uid=}) or only good or stopped ones ({@code result=}).
 *   <li>{@code GET /v1/taps/days?days=N}: taps per day for the last N days (default 14, at most 90).
 *   <li>{@code POST /v1/nfc/sessions} and {@code POST /v1/nfc/sessions/<id>}: set a tag up,
 *       reset or read it through a phone ({@link NfcRelay}). Only for the tokens named in {@code
 *       admin.provision.tokens}, and off when that is empty.
 *   <li>{@code GET /v1/tokens}: {@code {"you":"fold7","tokens":["fold7","laptop"]}}, the token names.
 *   <li>{@code DELETE /v1/tokens/<name>}: removes a token, as {@code knurctl admin token remove}
 *       does, so a phone can lock out a lost laptop. Only for the tokens in {@code
 *       admin.provision.tokens}.
 * </ul>
 *
 * <p>With {@link #manage} set, the API also serves the setup page in a browser: signing in with a
 * password ({@link AdminPassword}, {@link Sessions}) and changing the settings in {@link
 * HomeSettings}. These requests may use a session cookie instead of a token, and are allowed for
 * signed-in browsers, plaintext mode and the tokens in {@code admin.provision.tokens}. Every
 * request that changes something, and every request with a session cookie, must carry {@code
 * X-Knura-Request: 1} and, if it has an {@code Origin}, come from this same address. A web page on
 * another site can't add that header without the browser asking first, and knurd never says yes,
 * so other sites can't make a signed-in browser change settings (cross-site request forgery).
 * {@code POST /v1/phones/pair} pairs a phone from the page ({@link PhonePairing}). With {@link
 * #listenOnWifi} the same requests are also answered over TLS on every network, for phones.
 * See {@code docs/setup-api.md} for each request.
 */
final class AdminServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("knurd.admin");
    static final List<String> PROXY_HEADERS = List.of(
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP",
            "CF-Connecting-IP", "True-Client-IP", "Tailscale-User-Login", "Tailscale-Funnel-Request");

    /**
     * What /v1/status reports. {@code backup} reads when the master key was last backed up, or is
     * null when there is no master key to back up.
     */
    record Info(String keyMode, String publicUrl, String resolverOwner, int knots, Instant startedAt,
            Supplier<BackupRecord.Status> backup) {
        Info(String keyMode, String publicUrl, String resolverOwner, int knots, Instant startedAt) {
            this(keyMode, publicUrl, resolverOwner, knots, startedAt, null);
        }
    }

    /**
     * What to send back: JSON, or with {@code raw} set, those bytes as {@code type}, offered as a
     * download named {@code filename} when that is set.
     */
    private record Answer(int status, String json, String detail, byte[] raw, String type, String filename) {
        Answer(int status, String json, String detail) {
            this(status, json, detail, null, null, null);
        }
    }

    /**
     * Who is asking: a token's name ("browser" for a signed-in browser, null in plaintext mode),
     * whether they may change settings, and the browser's session id, if any.
     */
    private record Caller(String name, boolean canManage, String session) {}

    /** What the setup page needs: the password, browser sessions and the settings it changes. */
    record Manage(AdminPassword password, Sessions sessions, HomeSettings settings, Tailscale tailscale,
            PhonePairing phones, SetupCode setupCode) {
        Manage(AdminPassword password, Sessions sessions, HomeSettings settings, Tailscale tailscale,
                PhonePairing phones) {
            this(password, sessions, settings, tailscale, phones, null);
        }

        Manage(AdminPassword password, Sessions sessions, HomeSettings settings, Tailscale tailscale) {
            this(password, sessions, settings, tailscale, null);
        }

        Manage(AdminPassword password, Sessions sessions, HomeSettings settings) {
            this(password, sessions, settings, new Tailscale(null, 0));
        }
    }

    static final String REQUEST_HEADER = "X-Knura-Request";
    private static final String SESSION = "/v1/session";
    private static final String PASSWORD = "/v1/password";
    private static final int MAX_MANAGE_BODY = 4096;

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
    private volatile NfcRelay nfc;
    private volatile HttpsServer wifi;
    private volatile Manage manage;
    /** Whether an address is this computer; tests swap it to act as another device on the network. */
    java.util.function.Predicate<InetAddress> thisComputer = InetAddress::isLoopbackAddress;
    /** The time zone the dashboard's days are counted in; tests pin it. */
    ZoneId zone = ZoneId.systemDefault();

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
        server = tls == null ? HttpServer.create(address, 0) : https(address, tls);
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

    private static HttpsServer https(InetSocketAddress address, SSLContext tls) throws IOException {
        HttpsServer https = HttpsServer.create(address, 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters p = getSSLContext().getDefaultSSLParameters();
                p.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                params.setSSLParameters(p);
            }
        });
        return https;
    }

    /**
     * Also answers on every network on {@code port}, over TLS, with the same checks: the Wi-Fi
     * listener ({@code admin.lan.port}), so phones on the home network can pair and set tags up
     * while this computer keeps using plain HTTP on loopback. Safe to call while running.
     */
    synchronized void listenOnWifi(int port, SSLContext tls) throws IOException {
        if (wifi != null) {
            return;
        }
        HttpsServer w = https(new InetSocketAddress(port), tls);
        w.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        w.createContext("/", this::handle);
        w.start();
        wifi = w;
    }

    /** The Wi-Fi listener's port, or 0 when it is off. */
    int lanPort() {
        HttpsServer w = wifi;
        return w == null ? 0 : w.getAddress().getPort();
    }

    /** Setting tags up through a phone, or null when that is off. */
    NfcRelay nfc() {
        return nfc;
    }

    /** Turns setting tags up through a phone on, or swaps the relay, as pairing from the setup page does. */
    void setNfc(NfcRelay relay) {
        this.nfc = relay;
    }

    /** Turns on the setup page's requests; call before {@link #start}. */
    void manage(Manage manage) {
        this.manage = manage;
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
        HttpsServer w = wifi;
        if (w != null) {
            w.stop(0);
        }
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
        Headers headers = exchange.getRequestHeaders();
        String session = Sessions.fromCookie(headers.getFirst("Cookie"));
        String forgery = forgeryProblem(headers, method, path, session != null);
        if (!LocalAddresses.isLocalNetwork(remote)) {
            answer = new Answer(403, error("forbidden", "the admin API only answers this computer and the local network"),
                    "not a local address");
        } else if (proxied(exchange.getRequestHeaders()) != null) {
            answer = new Answer(403, error("forbidden", "the admin API must not be reached through a proxy or tunnel"),
                    "came through a proxy (" + proxied(exchange.getRequestHeaders()) + ")");
        } else if (tokens == null && !loopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
            answer = new Answer(403, error("forbidden", "in plaintext mode the admin API only answers requests for"
                    + " 127.0.0.1 or localhost"), "Host is not this computer");
        } else if (manage != null && SetupPage.serves(method, path)) {
            answer = null;
            SetupPage.send(exchange, path);
        } else if (forgery != null) {
            answer = new Answer(403, error("forbidden", forgery), "failed the browser request check");
        } else if (manage != null && path.equals(SESSION)) {
            answer = signIn(method, requestBody, remoteText, session, exchange);
            tokenName = answer.status() == 200 && "signed in".equals(answer.detail()) ? "browser" : null;
        } else if (manage != null && path.equals(PASSWORD) && !manage.password().isSet()) {
            answer = firstPassword(method, requestBody, remote, exchange);
            tokenName = answer.status() == 200 ? "browser" : null;
        } else if (tokens == null) {
            boolean signedIn = manage != null && manage.sessions().check(session);
            try {
                answer = route(method, path, exchange.getRequestURI().getRawQuery(),
                        new Caller(null, true, signedIn ? session : null), requestBody, exchange);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "admin request " + method + " " + path + " failed", e);
                answer = new Answer(500, error("error", null), "internal error");
            }
            if (answer.detail() == null) {
                answer = new Answer(answer.status(), answer.json(), "plaintext: no login", answer.raw(), answer.type(),
                        answer.filename());
            }
        } else if ("/v1/pair".equals(path)) {
            Duration wait = throttle.waitFor(remoteText);
            if (!"POST".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "POST");
                answer = new Answer(405, error("method_not_allowed", null), null);
            } else if (!wait.isZero()) {
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(Math.max(1, wait.toSeconds())));
                answer = new Answer(429, error("too_many_attempts", "too many bad codes; try again later"), "locked out");
            } else {
                answer = pair(requestBody, remoteText);
                if (answer.status() == 200) {
                    tokenName = answer.detail().substring("paired ".length());
                }
            }
        } else if (manage != null && manage.sessions().check(session)) {
            tokenName = "browser";
            try {
                answer = route(method, path, exchange.getRequestURI().getRawQuery(), new Caller("browser", true, session),
                        requestBody, exchange);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "admin request " + method + " " + path + " failed", e);
                answer = new Answer(500, error("error", null), "internal error");
            }
        } else {
            Optional<String> name = tokens.nameOf(bearer(exchange.getRequestHeaders()));
            Duration wait = throttle.waitFor(remoteText);
            if (name.isPresent()) {
                // A real token always works, so someone else's bad guesses can't lock you out.
                throttle.succeeded(remoteText);
                tokenName = name.get();
                try {
                    answer = route(method, path, exchange.getRequestURI().getRawQuery(),
                            new Caller(tokenName, allowsPhone(tokenName), null), requestBody, exchange);
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
        if (answer == null) {
            return; // the setup page's files, sent already; they hold nothing private, so they aren't audited
        }
        try {
            audit.record(remoteText, tokenName, method, path, answer.status(), answer.detail());
        } catch (IOException e) {
            LOG.log(Level.ERROR, "could not write the admin audit log " + audit.file() + "; refusing the request", e);
            answer = new Answer(500, error("error", "the audit log can't be written"), null);
        }
        if (answer.raw() != null) {
            sendRaw(exchange, answer);
        } else {
            send(exchange, answer.status(), answer.json());
        }
    }

    private static void sendRaw(HttpExchange exchange, Answer answer) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", answer.type());
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'");
        if (answer.filename() != null) {
            headers.set("Content-Disposition", "attachment; filename=\"" + answer.filename() + "\"");
        }
        exchange.sendResponseHeaders(answer.status(), answer.raw().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer.raw());
        }
    }

    private Answer pair(byte[] body, String remote) {
        Object code;
        try {
            code = FlatJson.parse(new String(body, StandardCharsets.UTF_8)).get("code");
        } catch (IllegalArgumentException e) {
            code = null;
        }
        Optional<TokenStore.Paired> paired;
        try {
            paired = tokens.pair(code instanceof String s ? s : null, clock.instant());
        } catch (IOException e) {
            LOG.log(Level.ERROR, "could not pair a phone", e);
            return new Answer(500, error("error", "knurd couldn't save the phone's token"), "pairing failed");
        }
        if (paired.isEmpty()) {
            throttle.failed(remote);
            return new Answer(401, error("unauthorized", "that pairing code is wrong, used or more than "
                    + AdminTokens.PAIR_TIME.toMinutes() + " minutes old; make a new one with knurctl admin pair-phone"),
                    "bad pairing code");
        }
        return new Answer(200, "{\"name\":" + Json.quote(paired.get().name()) + ",\"token\":"
                + Json.quote(paired.get().token()) + "}", "paired " + paired.get().name());
    }

    private Answer route(String method, String path, String query, Caller caller, byte[] body,
            HttpExchange exchange) {
        String tokenName = caller.name();
        if (manage != null && isManagePath(path)) {
            return manageRoute(method, path, caller, body, exchange);
        }
        if (path.equals(NFC_SESSIONS) || path.startsWith(NFC_SESSIONS + "/")) {
            return nfc(method, path, tokenName, body, exchange);
        }
        if (path.startsWith(TOKENS + "/")) {
            return removeToken(method, path.substring(TOKENS.length() + 1), tokenName, exchange);
        }
        boolean known = switch (path) {
            case "/v1/whoami", "/v1/status", "/v1/tags", "/v1/taps", "/v1/taps/days", TOKENS -> true;
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
            case "/v1/taps/days" -> {
                Integer days = number(query, "days", 14, RecentTaps.MAX_DAYS);
                yield days == null
                        ? new Answer(400, error("malformed", "days must be a number from 1 to " + RecentTaps.MAX_DAYS), null)
                        : ok(tapDays(days));
            }
            default -> {
                Integer limit = limit(query);
                Predicate<RecentTaps.Entry> filter = tapFilter(query);
                if (limit == null) {
                    yield new Answer(400, error("malformed", "limit must be a number from 1 to " + RecentTaps.MAX_LIMIT), null);
                }
                yield filter == null
                        ? new Answer(400, error("malformed", "uid must be a tag UID and result ok or stopped"), null)
                        : ok(taps(limit, filter));
            }
        };
    }

    private boolean allowsPhone(String tokenName) {
        NfcRelay relay = nfc;
        return relay != null && relay.allows(tokenName);
    }

    private Answer nfc(String method, String path, String tokenName, byte[] body, HttpExchange exchange) {
        NfcRelay nfc = this.nfc;
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
        if (!allowsPhone(tokenName)) {
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
        Manage m = manage;
        String name = "";
        if (m != null) {
            try {
                name = ",\"name\":" + Json.quote(m.settings().name());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "could not read knura.name", e);
            }
        }
        return "{\"status\":\"up\",\"uptimeSeconds\":" + uptime + ",\"keys\":" + Json.quote(info.keyMode())
                + ",\"publicUrl\":" + Json.quote(info.publicUrl()) + ",\"resolverOwner\":" + Json.quote(info.resolverOwner())
                + ",\"knots\":" + (m == null ? info.knots() : m.settings().knotCount())
                + ",\"tags\":" + counters.snapshot().size() + name + backup() + "}";
    }

    /** {@code ,"backup":{"file":..,"sheet":..}} with ISO times or null, or nothing without a master key. */
    private String backup() {
        if (info.backup() == null) {
            return "";
        }
        BackupRecord.Status s = info.backup().get();
        return ",\"backup\":{\"file\":" + Json.quote(s.file().map(Instant::toString).orElse(null))
                + ",\"sheet\":" + Json.quote(s.sheet().map(Instant::toString).orElse(null)) + "}";
    }

    private String tags() {
        if (manage != null) {
            return manageTags();
        }
        StringBuilder json = new StringBuilder("{\"tags\":[");
        String sep = "";
        for (Map.Entry<String, Integer> e : counters.snapshot().entrySet()) {
            json.append(sep).append("{\"uid\":").append(Json.quote(e.getKey()))
                    .append(",\"lastCounter\":").append(e.getValue()).append('}');
            sep = ",";
        }
        return json.append("]}").toString();
    }

    private String taps(int limit, Predicate<RecentTaps.Entry> filter) {
        StringBuilder json = new StringBuilder("{\"taps\":[");
        String sep = "";
        for (RecentTaps.Entry e : taps.latest(limit, filter)) {
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

    /** The limit query parameter, 50 when absent, or null when it is not a number from 1 to {@link RecentTaps#MAX_LIMIT}. */
    static Integer limit(String rawQuery) {
        return number(rawQuery, "limit", 50, RecentTaps.MAX_LIMIT);
    }

    /** A number query parameter, {@code absent} when it isn't there, or null when it is not from 1 to {@code max}. */
    static Integer number(String rawQuery, String name, int absent, int max) {
        Map<String, String> params = KnurdServer.parseQuery(rawQuery);
        if (params == null) {
            return null;
        }
        String value = params.get(name);
        if (value == null) {
            return absent;
        }
        try {
            int n = Integer.parseInt(value);
            return n >= 1 && n <= max ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Which taps {@code GET /v1/taps} lists: {@code uid=<UID>} for one tag, {@code result=ok} or
     * {@code result=stopped} (copied, forged or broken). Null when either is malformed.
     */
    static Predicate<RecentTaps.Entry> tapFilter(String rawQuery) {
        Map<String, String> params = KnurdServer.parseQuery(rawQuery);
        if (params == null) {
            return null;
        }
        String uid = params.get("uid");
        String result = params.get("result");
        if (uid != null && !uid.matches("[0-9A-Fa-f]{14}") || result != null && !result.matches("ok|stopped")) {
            return null;
        }
        String want = uid == null ? null : uid.toUpperCase(Locale.ROOT);
        return e -> (want == null || want.equals(e.uid()))
                && (result == null || e.stopped() == "stopped".equals(result));
    }

    /**
     * {@code GET /v1/taps/days?days=14}: taps per day, oldest first, in this computer's time zone,
     * for the dashboard's charts.
     */
    private String tapDays(int count) {
        RecentTaps.Days d = taps.days(count, zone);
        StringBuilder json = new StringBuilder("{\"zone\":").append(Json.quote(zone.getId())).append(",\"days\":[");
        for (int i = 0; i < count; i++) {
            json.append(i == 0 ? "" : ",").append("{\"day\":\"").append(d.days().get(i)).append("\",\"ok\":")
                    .append(d.ok()[i]).append(",\"stopped\":").append(d.stopped()[i]).append('}');
        }
        json.append("],\"tags\":{");
        String sep = "";
        for (Map.Entry<String, int[]> e : d.tags().entrySet()) {
            json.append(sep).append(Json.quote(e.getKey())).append(":[");
            for (int i = 0; i < count; i++) {
                json.append(i == 0 ? "" : ",").append(e.getValue()[i]);
            }
            json.append(']');
            sep = ",";
        }
        return json.append("}}").toString();
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

    // ---- the setup page ----

    /** {@code POST /v1/tags/<UID>}: the Knura app sharing a tag's new name. */
    private static final java.util.regex.Pattern APP_RENAME = java.util.regex.Pattern.compile("/v1/tags/[0-9A-Fa-f]{14}");

    /** Whether a path belongs to the setup page's requests. */
    static boolean isManagePath(String path) {
        return path.equals(SESSION) || path.equals(PASSWORD) || path.equals("/v1/settings")
                || path.equals("/v1/actions/reload") || path.equals("/v1/connections") || path.equals("/v1/reach") || path.equals("/v1/tailscale") || path.equals("/v1/tailscale/funnel")
                || path.equals("/v1/phones/pair") || path.startsWith("/v1/phones/")
                || path.startsWith("/v1/backup/")
                || path.startsWith("/v1/connections/") || path.startsWith("/v1/tags/");
    }

    /**
     * Why a request may have been sent by another web site in the owner's browser, or null. Only
     * requests that change something, or carry a session cookie, are checked: they must have
     * {@code X-Knura-Request: 1}, and an {@code Origin}, if any, must be this same address.
     */
    static String forgeryProblem(Headers headers, String method, String path, boolean hasCookie) {
        if (!isManagePath(path) && !hasCookie) {
            return null; // knurctl and the phone app, which never send cookies
        }
        if (!hasCookie && "POST".equals(method) && APP_RENAME.matcher(path).matches() && bearer(headers) != null) {
            // The Knura app renaming a tag, the same request it sends a hosted account, which has
            // no such header. It carries a token, not a cookie, and another web site can't make
            // the browser send a token, so it can't be forged. Every other change needs the header.
            return null;
        }
        boolean changes = !"GET".equals(method) && !"HEAD".equals(method);
        if (!changes && !hasCookie) {
            return null;
        }
        if (!"1".equals(headers.getFirst(REQUEST_HEADER))) {
            return "browser requests need the " + REQUEST_HEADER + ": 1 header";
        }
        String origin = headers.getFirst("Origin");
        if (origin != null && !sameOrigin(origin, headers.getFirst("Host"))) {
            return "this request came from another web site";
        }
        return null;
    }

    static boolean sameOrigin(String origin, String host) {
        if (host == null) {
            return false;
        }
        try {
            URI o = new URI(origin);
            if (!("http".equals(o.getScheme()) || "https".equals(o.getScheme())) || o.getRawAuthority() == null) {
                return false;
            }
            return o.getRawAuthority().equalsIgnoreCase(host.trim());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** {@code /v1/session}: GET says whether you're signed in, POST signs in, DELETE signs out. */
    private Answer signIn(String method, byte[] body, String remote, String session, HttpExchange exchange) {
        Manage m = manage;
        switch (method) {
            case "GET" -> {
                boolean set = m.password().isSet();
                String extra = "";
                if (!set && !thisComputer.test(exchange.getRemoteAddress().getAddress())) {
                    // From another computer the first password needs the setup code, if there is one.
                    extra = ",\"needsSetupCode\":true,\"setupCodeWaiting\":"
                            + (m.setupCode() != null && m.setupCode().waiting());
                }
                return new Answer(200, "{\"passwordSet\":" + set + ",\"signedIn\":"
                        + m.sessions().check(session) + extra + "}", "session check");
            }
            case "DELETE" -> {
                m.sessions().end(session);
                exchange.getResponseHeaders().add("Set-Cookie", cookie(exchange, "", 0));
                return new Answer(200, "{\"signedIn\":false}", "signed out");
            }
            case "POST" -> {
                Duration wait = throttle.waitFor(remote);
                if (!wait.isZero()) {
                    exchange.getResponseHeaders().set("Retry-After", String.valueOf(Math.max(1, wait.toSeconds())));
                    return new Answer(429, error("too_many_attempts", "too many wrong passwords; try again later"),
                            "locked out");
                }
                if (!m.password().isSet()) {
                    return new Answer(409, error("no_password", "no password is set yet; set one on the Knura computer"),
                            "no password yet");
                }
                Object password = fields(body).get("password");
                boolean ok;
                try {
                    ok = password instanceof String p && m.password().matches(p);
                } catch (IOException e) {
                    LOG.log(Level.ERROR, "could not read " + m.password().file(), e);
                    return new Answer(500, error("error", "knurd couldn't read its password file"), "password file error");
                }
                if (!ok) {
                    throttle.failed(remote);
                    return new Answer(401, error("unauthorized", "that password is wrong"), "wrong password");
                }
                throttle.succeeded(remote);
                exchange.getResponseHeaders().add("Set-Cookie", cookie(exchange, m.sessions().start(), Sessions.IDLE.toSeconds()));
                return new Answer(200, "{\"signedIn\":true}", "signed in");
            }
            default -> {
                exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                return new Answer(405, error("method_not_allowed", null), null);
            }
        }
    }

    /**
     * {@code POST /v1/password} before any password exists. Only this computer may set the first
     * one, so someone else on the network can't get there first; it signs the browser in.
     */
    private Answer firstPassword(String method, byte[] body, InetAddress remote, HttpExchange exchange) {
        if (!"POST".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "POST");
            return new Answer(405, error("method_not_allowed", null), null);
        }
        Map<String, Object> f = fields(body);
        boolean byCode = false;
        if (!thisComputer.test(remote)) {
            SetupCode code = manage.setupCode();
            if (code == null || !code.waiting()) {
                return new Answer(403, error("forbidden", "set the first password on the Knura computer itself"),
                        "first password from another computer");
            }
            String who = remote.getHostAddress();
            Duration wait = throttle.waitFor(who);
            if (!wait.isZero()) {
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(Math.max(1, wait.toSeconds())));
                return new Answer(429, error("too_many_attempts", "too many wrong setup codes; try again later"),
                        "locked out");
            }
            if (!code.matches(f.get("setupCode") instanceof String c ? c : null)) {
                throttle.failed(who);
                return new Answer(401, error("bad_setup_code", "that setup code is wrong. It's in the file "
                        + SetupCode.FILE + " in Knura's settings folder, and in Knura's log"), "wrong setup code");
            }
            byCode = true;
        }
        Object password = f.get("password");
        String problem = AdminPassword.problem(password instanceof String p ? p : null);
        if (problem != null) {
            return new Answer(400, error("malformed", "password: " + problem), "password too weak");
        }
        try {
            manage.password().set((String) password);
            if (byCode) {
                manage.setupCode().used();
            }
        } catch (IOException e) {
            LOG.log(Level.ERROR, "could not save the admin password", e);
            return new Answer(500, error("error", "knurd couldn't save the password"), "password not saved");
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie(exchange, manage.sessions().start(), Sessions.IDLE.toSeconds()));
        return new Answer(200, "{\"signedIn\":true}", byCode ? "first password set with the setup code" : "first password set");
    }

    private Answer manageRoute(String method, String path, Caller caller, byte[] body, HttpExchange exchange) {
        Manage m = manage;
        if (!caller.canManage()) {
            return new Answer(403, error("forbidden", "this token may not change settings; sign in on the setup page,"
                    + " or add the token to admin.provision.tokens"), "token may not manage");
        }
        if (body.length > MAX_MANAGE_BODY) {
            return new Answer(413, error("too_large", "request body over " + MAX_MANAGE_BODY + " bytes"), null);
        }
        Map<String, Object> f;
        try {
            f = body.length == 0 ? Map.of() : FlatJson.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return new Answer(400, error("malformed", "the body must be a flat JSON object: " + e.getMessage()), null);
        }
        String[] parts = path.substring(1).split("/", -1);
        try {
            if (path.equals("/v1/settings")) {
                if ("PUT".equals(method)) {
                    if (!f.containsKey("name") && !f.containsKey("publicUrl")) {
                        throw new IllegalArgumentException("name or publicUrl is missing");
                    }
                    if (f.containsKey("publicUrl")) {
                        m.settings().setPublicUrl(text(f, "publicUrl"));
                    }
                    if (f.containsKey("name")) {
                        m.settings().setName(text(f, "name"));
                    }
                    return new Answer(200, settingsJson(), f.containsKey("publicUrl") ? "changed public.url" : "changed name");
                }
                return only("GET", method, exchange, () -> new Answer(200, settingsJson(), null));
            }
            if (path.equals(PASSWORD)) {
                return only("POST", method, exchange, () -> changePassword(f, caller, exchange));
            }
            if (path.equals("/v1/backup/file")) {
                return only("POST", method, exchange, () -> {
                    Object pass = f.get("passphrase");
                    if (!(pass instanceof String p)) {
                        throw new IllegalArgumentException("passphrase is missing");
                    }
                    char[] chars = p.toCharArray();
                    try {
                        byte[] file = m.settings().backupFile(chars);
                        String day = clock.instant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
                        return new Answer(200, null, "made a backup file", file, "application/octet-stream",
                                "knura-backup-" + day + io.knura.core.backup.BackupFile.SUFFIX);
                    } finally {
                        java.util.Arrays.fill(chars, '\0');
                    }
                });
            }
            if (path.equals("/v1/backup/sheet")) {
                return only("POST", method, exchange, () -> new Answer(200, null, "made a recovery sheet",
                        m.settings().recoverySheet().getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8",
                        "knura-recovery-sheet.html"));
            }
            if (path.equals("/v1/tailscale")) {
                return only("GET", method, exchange, () -> new Answer(200, tailscaleJson(m.tailscale().status(), null), null));
            }
            if (path.equals("/v1/tailscale/funnel")) {
                return only("POST", method, exchange, () -> {
                    Tailscale.Funnel funnel = m.tailscale().turnOnFunnel();
                    Tailscale.Status st = m.tailscale().status();
                    if (funnel.on() && st.funnel() && st.url() != null && m.settings().publicUrl() == null) {
                        m.settings().setPublicUrl(st.url());
                    }
                    return new Answer(200, tailscaleJson(st, funnel), funnel.on() ? "turned on Tailscale Funnel" : "Tailscale Funnel not on");
                });
            }
            if (path.equals("/v1/phones/pair")) {
                return only("POST", method, exchange, () -> pairPhone(m, f));
            }
            if (parts.length == 3 && parts[1].equals("phones")) {
                String name = parts[2];
                return only("DELETE", method, exchange, () -> {
                    if (m.phones() == null || !m.phones().remove(name)) {
                        return new Answer(404, error("not_found", "no phone named " + name), null);
                    }
                    return new Answer(200, "{\"removed\":" + Json.quote(name) + "}", "removed phone " + name);
                });
            }
            if (path.equals("/v1/reach")) {
                return only("GET", method, exchange, () -> new Answer(200, reach(), null));
            }
            if (path.equals("/v1/actions/reload")) {
                return only("POST", method, exchange, () -> new Answer(200, "{\"knots\":" + m.settings().reload() + "}",
                        "reloaded actions"));
            }
            if (path.equals("/v1/connections")) {
                return only("GET", method, exchange, () -> new Answer(200, connectionsJson(), null));
            }
            if (parts.length == 5 && parts[1].equals("connections") && parts[4].equals("test")) {
                String type = parts[2];
                String id = parts[3];
                if (!HomeSettings.TYPES.contains(type)) {
                    return new Answer(404, error("not_found", null), null);
                }
                return only("POST", method, exchange, () -> {
                    io.knura.knurgn.Delivery d = m.settings().testConnection(type, id);
                    return new Answer(200, "{\"id\":" + Json.quote(id) + ",\"result\":"
                            + Json.quote(d.status().name().toLowerCase(Locale.ROOT)) + ",\"detail\":" + Json.quote(d.detail())
                            + "}", "test for " + type + " " + id + ": " + d.status().name().toLowerCase(Locale.ROOT));
                });
            }
            if (parts.length == 5 && parts[1].equals("connections") && parts[4].equals("secret")) {
                String type = parts[2];
                String id = parts[3];
                if (!HomeSettings.TYPES.contains(type)) {
                    return new Answer(404, error("not_found", null), null);
                }
                return only("POST", method, exchange, () -> new Answer(200, "{\"type\":" + Json.quote(type) + ",\"id\":"
                        + Json.quote(id) + ",\"secret\":" + Json.quote(m.settings().newConnectionSecret(type, id)) + "}",
                        "new secret for " + type + " " + id));
            }
            if (parts.length == 4 && parts[1].equals("connections")) {
                String type = parts[2];
                String id = parts[3];
                if (!HomeSettings.TYPES.contains(type)) {
                    return new Answer(404, error("not_found", null), null);
                }
                if ("DELETE".equals(method)) {
                    m.settings().removeConnection(type, id);
                    return new Answer(200, "{\"removed\":" + Json.quote(id) + "}", "removed " + type + " " + id);
                }
                return only("PUT", method, exchange, () -> {
                    Optional<String> secret = m.settings().putConnection(type, id, text(f, "url"));
                    return new Answer(200, "{\"type\":" + Json.quote(type) + ",\"id\":" + Json.quote(id)
                            + ",\"secret\":" + Json.quote(secret.orElse(null)) + "}",
                            (secret.isPresent() ? "added " : "changed ") + type + " " + id);
                });
            }
            if (parts.length == 3 && parts[1].equals("tags")) {
                String uid = HomeSettings.uid(parts[2]);
                if ("POST".equals(method)) {
                    return syncName(m, uid, f);
                }
                exchange.getResponseHeaders().set("Allow", "PUT, POST");
                return only("PUT", method, exchange, () -> {
                    m.settings().setLabel(uid, optionalText(f, "label"), optionalText(f, "icon"));
                    return new Answer(200, "{\"uid\":" + Json.quote(uid) + "}", "labelled " + uid);
                });
            }
            if (parts.length == 4 && parts[1].equals("tags") && parts[3].equals("actions")) {
                String uid = HomeSettings.uid(parts[2]);
                return only("PUT", method, exchange, () -> {
                    Object list = f.get("actions");
                    if (list != null && !(list instanceof String)) {
                        throw new IllegalArgumentException("actions: a comma-separated list of connector names");
                    }
                    List<String> ids = HomeSettings.split((String) list);
                    m.settings().setTagActions(uid, ids);
                    return new Answer(200, "{\"uid\":" + Json.quote(uid) + ",\"actions\":" + jsonList(ids) + "}",
                            "set actions for " + uid);
                });
            }
            if (parts.length == 4 && parts[1].equals("tags") && parts[3].equals("test")) {
                String uid = HomeSettings.uid(parts[2]);
                return only("POST", method, exchange, () -> new Answer(200, "{\"started\":" + m.settings().test(uid) + "}",
                        "test tap for " + uid));
            }
            return new Answer(404, error("not_found", null), null);
        } catch (HomeSettings.NotFound e) {
            return new Answer(404, error("not_found", e.getMessage()), null);
        } catch (HomeSettings.InUse e) {
            return new Answer(409, error("in_use", e.getMessage()), null);
        } catch (IllegalArgumentException e) {
            return new Answer(400, error("malformed", e.getMessage()), "refused a change");
        } catch (IOException e) {
            LOG.log(Level.ERROR, "admin request " + method + " " + path + " couldn't change a file", e);
            return new Answer(500, error("error", "knurd couldn't save the change: " + e.getMessage()), "save failed");
        }
    }

    @FunctionalInterface
    private interface Work {
        Answer run() throws IOException;
    }

    private static Answer only(String allowed, String method, HttpExchange exchange, Work work) throws IOException {
        if (!allowed.equals(method)) {
            exchange.getResponseHeaders().set("Allow", allowed);
            return new Answer(405, error("method_not_allowed", null), null);
        }
        return work.run();
    }

    /** {@code POST /v1/password} once one is set: a browser must give the current one too. */
    private Answer changePassword(Map<String, Object> f, Caller caller, HttpExchange exchange) throws IOException {
        Manage m = manage;
        if (caller.session() != null) {
            Object current = f.get("current");
            if (!(current instanceof String c) || !m.password().matches(c)) {
                return new Answer(401, error("unauthorized", "the current password is wrong"), "wrong current password");
            }
        }
        Object password = f.get("password");
        String problem = AdminPassword.problem(password instanceof String p ? p : null);
        if (problem != null) {
            return new Answer(400, error("malformed", "password: " + problem), "password too weak");
        }
        m.password().set((String) password);
        m.sessions().endAll();
        if (caller.session() != null) {
            exchange.getResponseHeaders().add("Set-Cookie", cookie(exchange, m.sessions().start(), Sessions.IDLE.toSeconds()));
        }
        return new Answer(200, "{\"changed\":true}", "changed password; signed everyone else out");
    }

    private String settingsJson() throws IOException {
        Manage m = manage;
        return "{\"name\":" + Json.quote(m.settings().name()) + ",\"passwordSet\":" + m.password().isSet()
                + ",\"phones\":" + jsonList(m.phones() == null ? m.settings().phones() : m.phones().paired()) + ",\"publicUrl\":" + Json.quote(m.settings().publicUrl())
                + "}";
    }

    /** {@code POST /v1/phones/pair}: gets knurd ready for a phone and makes its pairing code. */
    private Answer pairPhone(Manage m, Map<String, Object> f) throws IOException {
        if (m.phones() == null) {
            return new Answer(404, error("not_found", null), null);
        }
        Object address = f.get("address");
        PhonePairing.Started p;
        try {
            p = m.phones().start(text(f, "name"), address instanceof String a ? a : null);
        } catch (PhonePairing.NotReady e) {
            return new Answer(409, error(e.code, e.getMessage()), "can't pair a phone: " + e.code);
        }
        StringBuilder qr = new StringBuilder("[");
        for (int y = 0; y < p.qr().length; y++) {
            StringBuilder row = new StringBuilder();
            for (boolean dark : p.qr()[y]) {
                row.append(dark ? '1' : '0');
            }
            qr.append(y == 0 ? "" : ",").append('"').append(row).append('"');
        }
        qr.append(']');
        return new Answer(200, "{\"name\":" + Json.quote(p.name()) + ",\"link\":" + Json.quote(p.link())
                + ",\"qr\":" + qr + ",\"url\":" + Json.quote(p.url()) + ",\"address\":" + Json.quote(p.address())
                + ",\"addresses\":" + jsonList(p.addresses()) + ",\"fingerprint\":" + Json.quote(p.fingerprint())
                + ",\"expires\":" + Json.quote(p.expires().toString()) + ",\"firewall\":" + Json.quote(p.firewall()) + "}",
                "pairing code for " + p.name());
    }

    private static String tailscaleJson(Tailscale.Status s, Tailscale.Funnel f) {
        String json = "{\"state\":" + Json.quote(s.state()) + ",\"loginUrl\":" + Json.quote(s.loginUrl())
                + ",\"dnsName\":" + Json.quote(s.dnsName()) + ",\"url\":" + Json.quote(s.url())
                + ",\"httpsCerts\":" + s.httpsCerts() + ",\"funnel\":" + s.funnel() + ",\"detail\":" + Json.quote(s.detail());
        if (f != null) {
            json += ",\"fixUrl\":" + Json.quote(f.fixUrl()) + ",\"fixCommand\":" + Json.quote(f.fixCommand())
                    + ",\"funnelDetail\":" + Json.quote(f.detail());
        }
        return json + "}";
    }

    /**
     * {@code GET /v1/reach}: whether phones on the internet can reach this knurd, by asking for
     * {@code <public.url>/health} the way a phone would, from outside in.
     */
    private String reach() {
        String url = info.publicUrl();
        try {
            url = manage.settings().publicUrl(); // what knurd.properties says now, even before a restart
        } catch (IOException | IllegalArgumentException e) {
            // keep the address knurd started with
        }
        boolean restart = !java.util.Objects.equals(url, info.publicUrl());
        String head = "{\"publicUrl\":" + Json.quote(url) + ",\"resolverOwner\":" + Json.quote(info.resolverOwner())
                + ",\"restart\":" + restart;
        if (url == null) {
            return head + ",\"reachable\":false,\"detail\":\"public.url is not set\"}";
        }
        String detail;
        boolean ok = false;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            java.net.http.HttpResponse<Void> r = client.send(java.net.http.HttpRequest.newBuilder(URI.create(url + "/health"))
                    .timeout(Duration.ofSeconds(8)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            ok = r.statusCode() == 200;
            detail = ok ? "answered" : "answered " + r.statusCode();
        } catch (IOException e) {
            detail = "no answer yet";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detail = "interrupted";
        }
        return head + ",\"reachable\":" + ok + ",\"detail\":" + Json.quote(detail) + "}";
    }

    private String connectionsJson() throws IOException {
        StringBuilder json = new StringBuilder("{\"connections\":[");
        String sep = "";
        Map<String, LiveActions.Last> last = manage.settings().lastResults();
        for (HomeSettings.Connection c : manage.settings().connections()) {
            LiveActions.Last l = last.get(c.id());
            json.append(sep).append("{\"type\":").append(Json.quote(c.type())).append(",\"id\":").append(Json.quote(c.id()))
                    .append(",\"url\":").append(Json.quote(c.url())).append(",\"usedBy\":").append(jsonList(c.usedBy()))
                    .append(",\"last\":").append(l == null ? "null" : "{\"time\":\"" + l.time() + "\",\"result\":"
                            + Json.quote(l.outcome()) + ",\"detail\":" + Json.quote(l.detail()) + ",\"test\":" + l.test() + "}")
                    .append('}');
            sep = ",";
        }
        return json.append("]}").toString();
    }

    /** A rename from the Knura app: {@code {"name": "..", "changedAt": <ms>}}; the newest change wins. */
    private Answer syncName(Manage m, String uid, Map<String, Object> f) throws IOException {
        if (!knownTag(m, uid)) {
            return new Answer(404, error("not_found", "knurd doesn't know this tag"), null);
        }
        Object name = f.get("name");
        Object at = f.get("changedAt");
        if (!(name instanceof String n)) {
            throw new IllegalArgumentException("name is missing");
        }
        if (!(at instanceof Number t)) {
            throw new IllegalArgumentException("changedAt is missing");
        }
        HomeSettings.Named standing = m.settings().syncName(uid, n, t.longValue());
        return new Answer(200, "{\"uid\":" + Json.quote(uid) + ",\"name\":" + Json.quote(standing.name())
                + ",\"nameChanged\":" + (standing.changedAt() == 0 ? "null" : Long.toString(standing.changedAt())) + "}",
                "named " + uid + " from the app");
    }

    private boolean knownTag(Manage m, String uid) throws IOException {
        return counters.snapshot().containsKey(uid) || m.settings().labels().containsKey(uid)
                || m.settings().tagActions().containsKey(uid);
    }

    /** Every tag knurd knows of, from its counters, labels and actions, with all three. */
    private String manageTags() {
        Map<String, Integer> seen = counters.snapshot();
        Map<String, HomeSettings.Label> labels = manage.settings().labels();
        Map<String, List<String>> actions;
        try {
            actions = manage.settings().tagActions();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not read the actions file", e);
            actions = Map.of();
        }
        java.util.TreeSet<String> uids = new java.util.TreeSet<>(seen.keySet());
        uids.addAll(labels.keySet());
        uids.addAll(actions.keySet());
        StringBuilder json = new StringBuilder("{\"tags\":[");
        String sep = "";
        for (String uid : uids) {
            HomeSettings.Label l = labels.get(uid);
            json.append(sep).append("{\"uid\":").append(Json.quote(uid))
                    .append(",\"lastCounter\":").append(seen.containsKey(uid) ? seen.get(uid).toString() : "null")
                    .append(",\"label\":").append(Json.quote(l == null ? null : l.label()))
                    // The Knura app reads "name" (and shares renames back) the same way it does
                    // with a hosted account.
                    .append(",\"name\":").append(Json.quote(l == null ? null : l.label()))
                    .append(",\"nameChanged\":").append(l == null || l.named() == 0 ? "null" : Long.toString(l.named()))
                    .append(",\"icon\":").append(Json.quote(l == null ? null : l.icon()))
                    .append(",\"actions\":").append(jsonList(actions.getOrDefault(uid, List.of()))).append('}');
            sep = ",";
        }
        return json.append("]}").toString();
    }

    private static String cookie(HttpExchange exchange, String value, long maxAge) {
        return Sessions.COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Strict"
                + (exchange instanceof com.sun.net.httpserver.HttpsExchange ? "; Secure" : "");
    }

    private static Map<String, Object> fields(byte[] body) {
        try {
            return FlatJson.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static String text(Map<String, Object> f, String name) {
        Object v = f.get(name);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return s;
    }

    private static String optionalText(Map<String, Object> f, String name) {
        Object v = f.get(name);
        if (v != null && !(v instanceof String)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return v == null || ((String) v).isBlank() ? null : (String) v;
    }

    private static String jsonList(List<String> items) {
        StringBuilder json = new StringBuilder("[");
        String sep = "";
        for (String s : items) {
            json.append(sep).append(Json.quote(s));
            sep = ",";
        }
        return json.append(']').toString();
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
