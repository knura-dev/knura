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
 */
public final class AdminTokens {
    public static final String PREFIX = "knurd_";
    private static final Pattern TOKEN = Pattern.compile("knurd_[A-Za-z0-9_-]{43}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");

    private AdminTokens() {}

    public static String generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
