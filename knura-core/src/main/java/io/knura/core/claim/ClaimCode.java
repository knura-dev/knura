package io.knura.core.claim;

import io.knura.core.crypto.Hex;
import io.knura.core.provision.TagKeySet;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/**
 * The secret on a ready-to-use tag's claim card. A seller sets the tag up with
 * {@link TagKeySet#claim} keys from a random 16-byte secret; the buyer types or scans the code and
 * their knurd swaps those keys for its own, after which the card is useless.
 *
 * <p>Written as 28 Crockford base32 characters in groups of four, e.g.
 * {@code XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX}: 26 for the secret, then 2 check characters (the
 * first 10 bits of its SHA-256) that catch typos. Reading is forgiving: case, spaces and dashes
 * don't matter, O reads as 0, and I and L read as 1. A card's QR code holds {@link #link}.
 */
public final class ClaimCode {
    /** Length of the secret in bytes. */
    public static final int SECRET_LENGTH = 16;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int SECRET_CHARS = 26;
    private static final int CHECK_CHARS = 2;
    private static final String LINK_PREFIX = "knura://claim?";

    private final byte[] secret;

    private ClaimCode(byte[] secret) {
        if (secret.length != SECRET_LENGTH) {
            throw new IllegalArgumentException("a claim secret is 16 bytes, got " + secret.length);
        }
        this.secret = secret.clone();
    }

    /** A new random claim code. */
    public static ClaimCode random(SecureRandom random) {
        byte[] secret = new byte[SECRET_LENGTH];
        random.nextBytes(secret);
        return new ClaimCode(secret);
    }

    /** The claim code holding this 16-byte secret. */
    public static ClaimCode of(byte[] secret) {
        return new ClaimCode(secret);
    }

    /**
     * Reads a claim code as printed on a card, or the {@code knura://claim?...} link from its QR
     * code. Throws IllegalArgumentException, saying what is wrong, for anything else.
     */
    public static ClaimCode parse(String text) {
        String code = text.strip();
        if (code.regionMatches(true, 0, LINK_PREFIX, 0, LINK_PREFIX.length())) {
            code = linkParam(code, "code");
            if (code == null) {
                throw new IllegalArgumentException("the claim link has no code");
            }
        }
        StringBuilder chars = new StringBuilder();
        for (char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '-', ' ' -> { }
                case 'O' -> chars.append('0');
                case 'I', 'L' -> chars.append('1');
                default -> {
                    if (ALPHABET.indexOf(c) < 0) {
                        throw new IllegalArgumentException("a claim code can't contain '" + c + "'");
                    }
                    chars.append(c);
                }
            }
        }
        if (chars.length() != SECRET_CHARS + CHECK_CHARS) {
            throw new IllegalArgumentException("a claim code has 28 letters and digits, got " + chars.length());
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < SECRET_CHARS; i++) {
            value = value.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(chars.charAt(i))));
        }
        if (value.bitLength() > SECRET_LENGTH * 8) {
            throw new IllegalArgumentException("not a valid claim code; check it for typos");
        }
        ClaimCode claim = new ClaimCode(toBytes(value));
        if (!claim.check().contentEquals(chars.subSequence(SECRET_CHARS, chars.length()))) {
            throw new IllegalArgumentException("this claim code doesn't check out; look for a typo");
        }
        return claim;
    }

    /** The UID in a {@code knura://claim?...} link, or null when there is none. */
    public static byte[] linkUid(String link) {
        String hex = link.regionMatches(true, 0, LINK_PREFIX, 0, LINK_PREFIX.length()) ? linkParam(link, "uid") : null;
        if (hex == null) {
            return null;
        }
        try {
            byte[] uid = Hex.decode(hex);
            return uid.length == 7 ? uid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The code as printed: 28 characters in groups of four. */
    public String formatted() {
        String plain = plain();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plain.length(); i += 4) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(plain, i, Math.min(i + 4, plain.length()));
        }
        return sb.toString();
    }

    /** What a claim card's QR code holds: {@code knura://claim?uid=<UID>&code=<code>}. */
    public String link(byte[] uid) {
        return LINK_PREFIX + "uid=" + Hex.encode(uid) + "&code=" + plain();
    }

    /** The keys the tag with this UID was set up with. */
    public TagKeySet keys(byte[] uid) {
        return TagKeySet.claim(secret, uid);
    }

    public byte[] secret() {
        return secret.clone();
    }

    /** Overwrites the secret in memory. */
    public void destroy() {
        Arrays.fill(secret, (byte) 0);
    }

    /** Never shows the secret, so a claim code can't leak into logs. */
    @Override
    public String toString() {
        return "ClaimCode[hidden]";
    }

    private String plain() {
        BigInteger value = new BigInteger(1, secret);
        char[] out = new char[SECRET_CHARS];
        for (int i = SECRET_CHARS - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt(value.intValue() & 31);
            value = value.shiftRight(5);
        }
        return new String(out) + check();
    }

    private String check() {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(secret);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
        int bits = ((digest[0] & 0xFF) << 2) | ((digest[1] & 0xFF) >>> 6);
        return "" + ALPHABET.charAt(bits >>> 5) + ALPHABET.charAt(bits & 31);
    }

    private static byte[] toBytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[SECRET_LENGTH];
        int copy = Math.min(raw.length, SECRET_LENGTH);
        System.arraycopy(raw, raw.length - copy, out, SECRET_LENGTH - copy, copy);
        return out;
    }

    private static String linkParam(String link, String name) {
        String query = URI.create(link.replace(" ", "")).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
