package io.knura.knurgn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookConnectorTest {
    static final Secret SECRET = WebhookSignature.parseSecret("desk", "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T21:14:03.120Z"), ZoneOffset.UTC);
    static final TapEvent EVENT = TapEvent.of("042D6B02936980", 61, "desk-candle", CLOCK.instant());

    @Test
    void sendsSignedEvent() throws Exception {
        try (Receiver receiver = new Receiver(call -> 204)) {
            Delivery d = new WebhookConnector("desk", receiver.url(), SECRET, CLOCK).deliver(EVENT, Duration.ofSeconds(5));
            assertEquals(Delivery.Status.DELIVERED, d.status());
            assertEquals("HTTP 204", d.detail());

            Receiver.Request r = receiver.next(1000);
            assertEquals("POST", r.method());
            assertEquals("application/json", r.headers().get("content-type"));
            assertEquals("042D6B02936980:61", r.headers().get("webhook-id"));
            assertEquals("1791321243", r.headers().get("webhook-timestamp"));
            assertEquals(
                    "{\"type\":\"tap.verified\",\"timestamp\":\"2026-10-06T21:14:03.120Z\",\"data\":{"
                            + "\"id\":\"042D6B02936980:61\",\"knot\":\"desk-candle\",\"uid\":\"042D6B02936980\","
                            + "\"counter\":61,\"tappedAt\":\"2026-10-06T21:14:03.120Z\",\"action\":\"desk\"}}",
                    r.body());
            assertEquals(
                    WebhookSignature.sign(SECRET, "042D6B02936980:61", 1791321243L, r.body()),
                    r.headers().get("webhook-signature"));
        }
    }

    @Test
    void sortsResponsesIntoRetryOrRefuse() throws Exception {
        assertEquals(Delivery.Status.FAILED, statusFor(500));
        assertEquals(Delivery.Status.FAILED, statusFor(503));
        assertEquals(Delivery.Status.FAILED, statusFor(429));
        assertEquals(Delivery.Status.REFUSED, statusFor(401));
        assertEquals(Delivery.Status.REFUSED, statusFor(404));
    }

    @Test
    void neverFollowsRedirects() throws Exception {
        try (Receiver receiver = new Receiver(call -> 302)) {
            Delivery d = new WebhookConnector("desk", receiver.url(), SECRET, CLOCK).deliver(EVENT, Duration.ofSeconds(5));
            assertEquals(Delivery.Status.REFUSED, d.status());
            assertEquals(1, receiver.calls());
        }
    }

    @Test
    void networkErrorIsRetryableAndHidesTheAddress() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort(); // closed again, so nothing listens here
        }
        URI url = URI.create("http://127.0.0.1:" + port + "/secret-token-in-path");
        Delivery d = new WebhookConnector("desk", url, SECRET, CLOCK).deliver(EVENT, Duration.ofSeconds(5));
        assertEquals(Delivery.Status.FAILED, d.status());
        assertFalse(d.detail().contains("secret-token"));
    }

    @Test
    void requiresHttpsOutsideTheLocalNetwork() {
        WebhookConnector.checkUrl("a", URI.create("https://example.com/hook"));
        WebhookConnector.checkUrl("a", URI.create("http://localhost:8425/hook"));
        WebhookConnector.checkUrl("a", URI.create("http://127.0.0.1:8425/hook"));
        WebhookConnector.checkUrl("a", URI.create("http://192.168.1.20:1880/knura"));
        WebhookConnector.checkUrl("a", URI.create("http://10.0.0.5/hook"));
        WebhookConnector.checkUrl("a", URI.create("http://[::1]:8425/hook"));
        assertThrows(IllegalArgumentException.class, () -> WebhookConnector.checkUrl("a", URI.create("http://example.com/hook")));
        assertThrows(IllegalArgumentException.class, () -> WebhookConnector.checkUrl("a", URI.create("http://8.8.8.8/hook")));
        assertThrows(IllegalArgumentException.class, () -> WebhookConnector.checkUrl("a", URI.create("ftp://127.0.0.1/hook")));
        assertThrows(IllegalArgumentException.class, () -> WebhookConnector.checkUrl("a", URI.create("/relative")));
        assertTrue(WebhookConnector.isLocal("LOCALHOST"));
        assertFalse(WebhookConnector.isLocal("nodered.lan"));
    }

    private static Delivery.Status statusFor(int code) throws Exception {
        try (Receiver receiver = new Receiver(call -> code)) {
            Delivery.Status s = new WebhookConnector("desk", receiver.url(), SECRET, CLOCK)
                    .deliver(EVENT, Duration.ofSeconds(5))
                    .status();
            assertNotNull(receiver.next(1000));
            return s;
        }
    }
}
