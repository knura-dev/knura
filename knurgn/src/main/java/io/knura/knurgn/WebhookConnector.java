package io.knura.knurgn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sends a signed JSON POST to a web address for each tap. The body looks like:
 *
 * <pre>{@code
 * {"type":"tap.verified","timestamp":"2026-10-06T21:14:03.120Z",
 *  "data":{"id":"042D6B02936980:61","knot":"desk-candle","uid":"042D6B02936980","counter":61,
 *          "tappedAt":"2026-10-06T21:14:03.120Z","action":"desk-webhook"}}
 * }</pre>
 *
 * <p>Headers {@code webhook-id} (the tap id, the same on every retry), {@code webhook-timestamp}
 * and {@code webhook-signature} follow Standard Webhooks; see {@link WebhookSignature}. Receivers
 * should check the signature, refuse timestamps more than a few minutes off, and ignore ids they
 * have already handled.
 *
 * <p>The address must use HTTPS, except on this machine or a private network address
 * ({@code localhost}, {@code 127.0.0.1}, {@code 192.168.x.x} and so on). Redirects are never
 * followed, so a receiver cannot bounce a signed event somewhere else. The address is not logged,
 * since some services put a secret in it.
 */
public final class WebhookConnector implements Connector {
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final String id;
    private final URI url;
    private final Secret secret;
    private final Clock clock;
    private final HttpClient http;

    public WebhookConnector(String id, URI url, Secret secret, Clock clock) {
        checkUrl(id, url);
        this.id = id;
        this.url = url;
        this.secret = secret;
        this.clock = clock;
        this.http = client();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return "webhook";
    }

    @Override
    public Delivery deliver(TapEvent event, Duration timeout) {
        String body = body(event, id, clock);
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "knurgN")
                .header("webhook-id", event.id())
                .header("webhook-timestamp", Long.toString(timestamp))
                .header("webhook-signature", WebhookSignature.sign(secret, event.id(), timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(http, request);
    }

    /** An HTTP client that never follows redirects, so a receiver cannot bounce an event elsewhere. */
    static HttpClient client() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** Sends one request and sorts the answer into delivered, worth retrying, or refused. */
    static Delivery send(HttpClient http, HttpRequest request) {
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // Class name only: some exception messages include the address.
            return Delivery.failed(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Delivery.failed("interrupted");
        }
        int code = response.statusCode();
        String detail = "HTTP " + code;
        if (code >= 200 && code < 300) {
            return Delivery.delivered(detail);
        }
        if (code == 408 || code == 429 || code >= 500) {
            return Delivery.failed(detail);
        }
        return Delivery.refused(detail);
    }

    /** The JSON body. Knot names and connector ids are limited to safe characters by the config. */
    static String body(TapEvent event, String connectorId, Clock clock) {
        return "{\"type\":\"tap.verified\",\"timestamp\":\"" + clock.instant() + "\",\"data\":{"
                + "\"id\":\"" + event.id() + "\","
                + "\"knot\":\"" + event.knot() + "\","
                + "\"uid\":\"" + event.uid() + "\","
                + "\"counter\":" + event.counter() + ","
                + "\"tappedAt\":\"" + event.tappedAt() + "\","
                + "\"action\":\"" + connectorId + "\"}}";
    }

    static void checkUrl(String id, URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost();
        if (host == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("webhook." + id + ".url must be an http:// or https:// address");
        }
        if (scheme.equals("http") && !isLocal(host)) {
            throw new IllegalArgumentException("webhook." + id + ".url must use https://, except for localhost"
                    + " or a private network address such as 192.168.1.20");
        }
    }

    /** True for localhost and for loopback, private and link-local IP addresses written as numbers. */
    static boolean isLocal(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost")) {
            return true;
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        } else if (!IPV4.matcher(h).matches()) {
            return false; // a host name: we would have to trust DNS to know where it points
        }
        try {
            // Only IP literals reach here, so this parses without a DNS lookup.
            InetAddress address = InetAddress.getByName(h);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
