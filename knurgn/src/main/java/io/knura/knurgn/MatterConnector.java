package io.knura.knurgn;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Hands taps to knurgn-matter, the Matter bridge that runs next to knurd and shows each knot as a
 * contact sensor in Apple Home, Google Home, Alexa and SmartThings. The bridge "opens" the knot's
 * sensor for a few seconds, and the person's own routine in their app does the rest.
 *
 * <p>It is a signed webhook (see {@link WebhookConnector}) that may only go to this computer
 * ({@code localhost}, {@code 127.0.0.1} or {@code [::1]}), since the bridge listens there and
 * nowhere else.
 */
public final class MatterConnector implements Connector {
    public static final URI DEFAULT_URL = URI.create("http://127.0.0.1:5541/tap");

    private final WebhookConnector webhook;

    public MatterConnector(String id, URI url, Secret secret, Clock clock) {
        checkUrl(id, url);
        this.webhook = new WebhookConnector(id, url, secret, clock);
    }

    @Override
    public String id() {
        return webhook.id();
    }

    @Override
    public String type() {
        return "matter";
    }

    @Override
    public Delivery deliver(TapEvent event, Duration timeout) {
        return webhook.deliver(event, timeout);
    }

    static void checkUrl(String id, URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        boolean thisComputer = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!(scheme.equals("http") || scheme.equals("https")) || !thisComputer) {
            throw new IllegalArgumentException("matter." + id + ".url must be on this computer, like "
                    + DEFAULT_URL + ": the Matter bridge only listens there");
        }
    }
}
