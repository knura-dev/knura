package io.knura.knurgn;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Starts a Home Assistant automation through its webhook trigger: each tap is a JSON POST to
 * {@code <url>/api/webhook/<webhook id>}, with the same body as {@link WebhookConnector}, which
 * the automation reads as {@code trigger.json}.
 *
 * <p>Home Assistant cannot check a signature, so the webhook id is the key: it lives in the
 * secrets file, must be long and random, and is never logged. This needs no Home Assistant login
 * token, so knurgN can start the automations that listen on that id and nothing else.
 *
 * <p>The address is Home Assistant's own, such as {@code http://192.168.1.50:8123}, with no path.
 * Like webhooks, it must use HTTPS unless it is this computer or a private network address
 * written as numbers. Redirects are never followed.
 */
public final class HomeAssistantConnector implements Connector {
    /** Shortest webhook id accepted. {@code openssl rand -hex 24} makes a 48 character one. */
    static final int MIN_WEBHOOK_ID = 32;
    private static final Pattern WEBHOOK_ID = Pattern.compile("[A-Za-z0-9_-]{" + MIN_WEBHOOK_ID + ",128}");

    private final String id;
    private final URI hook;
    private final Clock clock;
    private final HttpClient http;

    public HomeAssistantConnector(String id, URI url, Secret webhookId, Clock clock) {
        checkUrl(id, url);
        if (url.getScheme().equalsIgnoreCase("http") && !isThisComputer(url.getHost())) {
            // The webhook id is the only key, and plain http shows it to anyone who can see this
            // network's traffic (threat S10).
            System.getLogger("knurgn").log(System.Logger.Level.WARNING, "homeassistant.{0}.url is plain http, so anyone"
                    + " on your network who can see its traffic can read the webhook id and start the automation;"
                    + " use Home Assistant''s https address if it has one", id);
        }
        String base = url.toString().replaceAll("/+$", "");
        this.id = id;
        this.hook = URI.create(base + "/api/webhook/" + new String(webhookId.value(), StandardCharsets.US_ASCII));
        this.clock = clock;
        this.http = WebhookConnector.client();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return "homeassistant";
    }

    @Override
    public Delivery deliver(TapEvent event, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(hook)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "knurgN")
                .header("webhook-id", event.id())
                .POST(HttpRequest.BodyPublishers.ofString(WebhookConnector.body(event, id, clock)))
                .build();
        return WebhookConnector.send(http, request);
    }

    /** Reads a webhook id from the secrets file. */
    static Secret parseWebhookId(String connectorId, String text) {
        String trimmed = text.trim();
        if (!WEBHOOK_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("the Home Assistant webhook id for " + connectorId + " must be "
                    + MIN_WEBHOOK_ID + " to 128 letters, digits, '_' or '-'; make one with: openssl rand -hex 24");
        }
        return new Secret(connectorId, trimmed.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isThisComputer(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]") || h.equals("::1");
    }

    static void checkUrl(String id, URI url) {
        String key = "homeassistant." + id + ".url";
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost();
        if (host == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException(key + " must be an http:// or https:// address, like http://192.168.1.50:8123");
        }
        String path = url.getRawPath() == null ? "" : url.getRawPath();
        if (!(path.isEmpty() || path.equals("/")) || url.getRawQuery() != null || url.getRawFragment() != null
                || url.getRawUserInfo() != null) {
            throw new IllegalArgumentException(key + " is Home Assistant's own address, like http://192.168.1.50:8123,"
                    + " without /api/webhook/...: the webhook id goes in the secrets file");
        }
        if (scheme.equals("http") && !WebhookConnector.isLocal(host)) {
            throw new IllegalArgumentException(key + " must use https://, except for localhost or a private network"
                    + " address written as numbers, such as 192.168.1.50 (not homeassistant.local)");
        }
    }
}
