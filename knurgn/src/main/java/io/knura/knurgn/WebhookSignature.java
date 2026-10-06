package io.knura.knurgn;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signatures in the Standard Webhooks format (https://www.standardwebhooks.com), so
 * receivers can check them with any Standard Webhooks library or a few lines of their own code.
 *
 * <p>The signature is HMAC-SHA256 over {@code id.timestamp.body} with the secret's bytes, sent as
 * {@code webhook-signature: v1,<base64>} next to {@code webhook-id} and {@code webhook-timestamp}
 * (Unix seconds). Secrets are written {@code whsec_<base64>}.
 */
public final class WebhookSignature {
    public static final String SECRET_PREFIX = "whsec_";
    /** Shortest secret accepted, in bytes. Standard Webhooks asks for 24 to 64. */
    static final int MIN_SECRET_BYTES = 24;
    static final int MAX_SECRET_BYTES = 64;

    private WebhookSignature() {}

    /** Decodes a {@code whsec_<base64>} secret. */
    public static Secret parseSecret(String connectorId, String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith(SECRET_PREFIX)) {
            throw new IllegalArgumentException("the secret for " + connectorId + " must start with " + SECRET_PREFIX);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(trimmed.substring(SECRET_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the secret for " + connectorId + " is not valid base64", e);
        }
        if (bytes.length < MIN_SECRET_BYTES || bytes.length > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("the secret for " + connectorId + " must be "
                    + MIN_SECRET_BYTES + " to " + MAX_SECRET_BYTES + " random bytes");
        }
        return new Secret(connectorId, bytes);
    }

    /** The {@code webhook-signature} header value: {@code v1,<base64 HMAC-SHA256>}. */
    public static String sign(Secret secret, String id, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.value(), "HmacSHA256"));
            byte[] signed = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(signed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing from this JDK", e);
        }
    }
}
