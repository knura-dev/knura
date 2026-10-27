package io.knura.core.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * API tokens for knurd's admin API. A token is {@code knurd_} plus 32 random bytes in base64url,
 * so it cannot be guessed and needs no slow password hash. knurd keeps only each token's SHA-256
 * in its tokens file ({@code <name>=sha256:<hex>} per line); the token itself lives only in the
 * knurctl command config made for it.
 *
 * <p>A phone is paired with a one-time pairing code ({@code knurp_...}) instead: knurctl keeps its
 * SHA-256 and an expiry time in the pairing file ({@link #PAIRING_FILE}, {@code <name>=sha256:<hex>
 * <expiry in epoch seconds>}), and the Knura app swaps the code for a new token at knurd's {@code
 * POST /v1/pair}, which ends the code. So the QR code knurctl shows is useless once used or expired.
 */
public final class AdminTokens {
    public static final String PREFIX = "knurd_";
    private static final Pattern TOKEN = Pattern.compile("knurd_[A-Za-z0-9_-]{43}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern PAIR_CODE = Pattern.compile("knurp_[A-Za-z0-9_-]{43}");
    private static final Pattern PAIRING = Pattern.compile("(sha256:[0-9a-f]{64}) ([0-9]{1,12})");
    /** The pairing file's name, next to the tokens file. */
    public static final String PAIRING_FILE = "knurd-admin-pairing.properties";
    /** How long a pairing code works: time to restart knurd and scan it. */
    public static final java.time.Duration PAIR_TIME = java.time.Duration.ofMinutes(15);

    /** A waiting pairing code: its hash and when it stops working. */
    public record Pairing(String codeHash, java.time.Instant expires) {}

    private AdminTokens() {}

    public static String generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String generatePairCode(SecureRandom random) {
        return "knurp_" + generate(random).substring(PREFIX.length());
    }

    public static boolean isWellFormedPairCode(String code) {
        return code != null && PAIR_CODE.matcher(code).matches();
    }

    /** Reads a pairing file's entries, name to pairing; throws for a damaged one. */
    public static Map<String, Pairing> parsePairing(Properties p) {
        Map<String, Pairing> pairing = new TreeMap<>();
        for (String name : p.stringPropertyNames()) {
            java.util.regex.Matcher m = PAIRING.matcher(p.getProperty(name).trim());
            if (!isValidName(name) || !m.matches()) {
                throw new IllegalArgumentException("bad pairing entry: " + name);
            }
            pairing.put(name, new Pairing(m.group(1), java.time.Instant.ofEpochSecond(Long.parseLong(m.group(2)))));
        }
        return pairing;
    }

    /** The pairing file text for {@code pairing}. */
    public static String formatPairing(Map<String, Pairing> pairing) {
        StringBuilder out = new StringBuilder(
                "# Phones waiting to pair with knurd: name=sha256 of the pairing code, then when it expires\n"
                        + "# (epoch seconds). Made by 'knurctl admin pair-phone'; knurd removes each one when it's used.\n");
        new TreeMap<>(pairing).forEach((name, p) ->
                out.append(name).append('=').append(p.codeHash()).append(' ').append(p.expires().getEpochSecond()).append('\n'));
        return out.toString();
    }

    public static boolean isWellFormed(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** Token names: 1 to 32 of a-z, 0-9, - and _, starting with a letter or digit. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** What the tokens file stores for {@code token}: {@code sha256:<64 hex digits>}. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /**
     * Reads a tokens file's entries, name to hash, sorted by name. Throws {@link
     * IllegalArgumentException} for a bad name or hash, so a damaged file is noticed rather than
     * silently letting fewer (or other) tokens in.
     */
    public static Map<String, String> parse(Properties p) {
        Map<String, String> tokens = new TreeMap<>();
        for (String name : p.stringPropertyNames()) {
            String hash = p.getProperty(name).trim();
            if (!isValidName(name)) {
                throw new IllegalArgumentException("bad token name in tokens file: " + name);
            }
            if (!HASH.matcher(hash).matches()) {
                throw new IllegalArgumentException("token " + name + " must be sha256:<64 hex digits>");
            }
            tokens.put(name, hash);
        }
        return tokens;
    }

    /** The tokens file text for {@code tokens} (name to hash). */
    public static String format(Map<String, String> tokens) {
        StringBuilder out = new StringBuilder(
                "# knurd admin API tokens: name=sha256 of the token. Made by 'knurctl admin token add'.\n"
                        + "# The tokens themselves are not stored here; knurd reads this file again when it changes.\n");
        new TreeMap<>(tokens).forEach((name, hash) -> out.append(name).append('=').append(hash).append('\n'));
        return out.toString();
    }

    /**
     * Whether two hashes are equal, in time that doesn't depend on where they differ, so timing
     * reveals nothing about stored hashes.
     */
    public static boolean sameHash(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
