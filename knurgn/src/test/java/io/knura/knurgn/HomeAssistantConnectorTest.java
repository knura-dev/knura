package io.knura.knurgn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HomeAssistantConnectorTest {
    static final String HOOK = "3f9c2a7e1b4d8f60a5c3e9b2d7f1a4c86e0b5d3a9f2c7e14";
    static final Secret WEBHOOK_ID = HomeAssistantConnector.parseWebhookId("living-room", HOOK);

    @Test
    void postsEventToWebhook() throws Exception {
        try (Receiver receiver = new Receiver(call -> 200)) {
            URI base = URI.create("http://127.0.0.1:" + receiver.url().getPort() + "/");
            Delivery d = new HomeAssistantConnector("living-room", base, WEBHOOK_ID, WebhookConnectorTest.CLOCK)
                    .deliver(WebhookConnectorTest.EVENT, Duration.ofSeconds(5));
            assertEquals(Delivery.Status.DELIVERED, d.status());

            Receiver.Request r = receiver.next(1000);
            assertEquals("POST", r.method());
            assertEquals("/api/webhook/" + HOOK, r.path());
            assertEquals("application/json", r.headers().get("content-type"));
            assertEquals("042D6B02936980:61", r.headers().get("webhook-id"));
            assertEquals(WebhookConnector.body(WebhookConnectorTest.EVENT, "living-room", WebhookConnectorTest.CLOCK), r.body());
            // No signature: Home Assistant can't check one, and the webhook id is the key.
            assertEquals("null", r.headers().get("webhook-signature"));
        }
    }

    @Test
    void neverFollowsRedirects() throws Exception {
        try (Receiver receiver = new Receiver(call -> 302)) {
            URI base = URI.create("http://127.0.0.1:" + receiver.url().getPort());
            Delivery d = new HomeAssistantConnector("living-room", base, WEBHOOK_ID, WebhookConnectorTest.CLOCK)
                    .deliver(WebhookConnectorTest.EVENT, Duration.ofSeconds(5));
            assertEquals(Delivery.Status.REFUSED, d.status());
            assertEquals(1, receiver.calls());
        }
    }

    @Test
    void checksAddress() {
        for (String ok : new String[] {
                "http://192.168.1.50:8123", "http://192.168.56.101:8123/", "http://10.0.0.5:8123",
                "http://localhost:8123", "https://ha.example.com"}) {
            assertDoesNotThrow(() -> HomeAssistantConnector.checkUrl("lr", URI.create(ok)), ok);
        }
        for (String bad : new String[] {
                "http://homeassistant.local:8123", "http://ha.example.com", "ftp://192.168.1.50",
                "http://192.168.1.50:8123/api/webhook/abc", "https://ha.example.com/?x=1",
                "https://user:pw@ha.example.com", "192.168.1.50:8123"}) {
            assertThrows(IllegalArgumentException.class, () -> HomeAssistantConnector.checkUrl("lr", URI.create(bad)), bad);
        }
    }

    @Test
    void checksWebhookIdWithoutShowingIt() {
        for (String bad : new String[] {"short-id", "has spaces in it but is long enough 1234567890", "a/b".repeat(20)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> HomeAssistantConnector.parseWebhookId("lr", bad), bad);
            assertTrue(e.getMessage().contains("openssl rand -hex 24"), e.getMessage());
            assertFalse(e.getMessage().contains(bad), e.getMessage());
        }
        assertFalse(WEBHOOK_ID.toString().contains(HOOK));
    }
}
