package io.knura.core.tap;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * A tap signed by a paired phone, which says who tapped. The tap itself is still checked the usual
 * way (the tag's MAC and its counter); the signature only adds which phone sent it.
 *
 * <p>Each paired phone has up to two <b>tap keys</b>, EC P-256 key pairs made in the phone's secure
 * hardware, whose private half never leaves it:
 *
 * <ul>
 *   <li>{@link Kind#EVERYDAY}: works whenever the phone is unlocked, so a tap is just "unlock, tap".
 *   <li>{@link Kind#CONFIRM}: the phone only lets it sign right after a fingerprint or screen lock,
 *       every single time. A server that sees this key's signature knows the person confirmed it,
 *       without having to trust the app's screens.
 * </ul>
 *
 * <p>The phone signs {@link #message}: a fixed label, the tag link's query (exactly as read from
 * the tag), the phone's name, the time and the key kind, one per line. The tag's counter makes each
 * tap usable once, and the time keeps a signed tap from being held back and sent much later.
 */
public final class TapSignature {
    /** How far the phone's clock may be from the server's. */
    public static final Duration MAX_SKEW = Duration.ofSeconds(60);
    private static final String LABEL = "knura-tap-v1";
    private static final int MAX_QUERY = 512;

    private TapSignature() {}

    /** Which of a phone's two tap keys signed. */
    public enum Kind {
        EVERYDAY("everyday"),
        CONFIRM("confirm");

        private final String jsonName;

        Kind(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }

        /** The kind called {@code name}, or null. */
        public static Kind of(String name) {
            for (Kind k : values()) {
                if (k.jsonName.equals(name)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** What the phone signs. */
    public static byte[] message(String query, String phone, long epochSeconds, Kind kind) {
        return (LABEL + "\n" + query + "\n" + phone + "\n" + epochSeconds + "\n" + kind.jsonName())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Whether {@code query} is a sane tag link query to sign or check: short, one line, ASCII. */
    public static boolean saneQuery(String query) {
        if (query == null || query.isEmpty() || query.length() > MAX_QUERY) {
            return false;
        }
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (ch <= 0x20 || ch >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** Whether a tap signed at {@code epochSeconds} is close enough to {@code now}. */
    public static boolean fresh(long epochSeconds, Instant now) {
        long skew = Math.abs(now.getEpochSecond() - epochSeconds);
        return skew <= MAX_SKEW.toSeconds();
    }

    /**
     * Reads a phone's public tap key: base64 of its X.509 SubjectPublicKeyInfo, as Android's
     * {@code PublicKey.getEncoded()} gives it. Only EC P-256 keys are accepted.
     *
     * @throws IllegalArgumentException if it isn't one
     */
    public static PublicKey parsePublicKey(String base64) {
        if (base64 == null || base64.length() > 400) {
            throw new IllegalArgumentException("not a tap key");
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof ECPublicKey ec) || !isP256(ec)) {
                throw new IllegalArgumentException("a tap key must be an EC P-256 key");
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw e.getMessage() != null && e.getMessage().startsWith("a tap key")
                    ? e : new IllegalArgumentException("not a tap key");
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a tap key");
        }
    }

    /** Whether {@code base64Signature} (DER, as Android's SHA256withECDSA makes it) is right. */
    public static boolean verify(PublicKey key, byte[] message, String base64Signature) {
        if (base64Signature == null || base64Signature.length() > 200) {
            return false;
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64Signature);
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(message);
            return s.verify(der);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    private static final BigInteger P256_P =
            new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);

    private static boolean isP256(ECPublicKey key) {
        var params = key.getParams();
        return params.getCurve().getField().getFieldSize() == 256
                && params.getCurve().getField() instanceof java.security.spec.ECFieldFp fp
                && fp.getP().equals(P256_P)
                && params.getOrder().bitLength() == 256;
    }
}
