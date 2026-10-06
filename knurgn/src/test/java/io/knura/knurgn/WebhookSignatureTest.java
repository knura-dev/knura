package io.knura.knurgn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {
    @Test
    void matchesStandardWebhooksExample() {
        // The example from the Standard Webhooks spec and its reference libraries' tests.
        Secret secret = WebhookSignature.parseSecret("test", "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        assertEquals(
                "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=",
                WebhookSignature.sign(secret, "msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330L, "{\"test\": 2432232314}"));
    }

    @Test
    void rejectsBadSecrets() {
        assertThrows(IllegalArgumentException.class, () -> WebhookSignature.parseSecret("a", "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"));
        assertThrows(IllegalArgumentException.class, () -> WebhookSignature.parseSecret("a", "whsec_not base64!"));
        assertThrows(IllegalArgumentException.class, () -> WebhookSignature.parseSecret("a", "whsec_c2hvcnQ="));
    }

    @Test
    void secretNeverPrintsItsValue() {
        Secret secret = WebhookSignature.parseSecret("desk", "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        assertEquals("Secret[desk]", secret.toString());
        assertFalse(String.valueOf(secret).contains("MfKQ"));
    }
}
