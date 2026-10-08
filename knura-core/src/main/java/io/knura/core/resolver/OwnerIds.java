package io.knura.core.resolver;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Owner IDs: the short name in a resolver tap URL, {@code https://tap.knura.io/<owner>}. Ten
 * characters from a 32-letter alphabet without l, o, 0 or 1, so 50 random bits: nobody can guess
 * an ID before its owner registers it.
 */
public final class OwnerIds {
    static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    public static final int LENGTH = 10;
    private static final Pattern VALID = Pattern.compile("[a-km-np-z2-9]{" + LENGTH + "}");

    private OwnerIds() {}

    public static String generate(SecureRandom random) {
        StringBuilder id = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }
}
