package io.knura.core.log;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides secrets in a log line: the last line of defence if a message or an exception ever quotes
 * one. Code should still never pass a secret to a logger; this catches mistakes.
 *
 * <p>Covers admin tokens ({@code knurd_...}), home link tokens ({@code knlink_...}), claim codes
 * ({@code XXXX-XXXX-...}), AES keys (32 hex digits), the {@code e=} and {@code c=} values from a
 * tap link, and {@code key=}, {@code token=}, {@code password=} and {@code secret=} settings, plus
 * {@code Bearer} headers. Tag UIDs (14 hex digits) and counters stay readable, since they are what
 * a log is for.
 */
public final class Redact {
    static final String HIDDEN = "[hidden]";

    private static final List<Pattern> WHOLE = List.of(
            // Admin and home link tokens, and webhook secrets.
            Pattern.compile("\\b(?:knurd|knlink|knura|whsec)_[A-Za-z0-9_+/=-]{16,}"),
            // Claim codes, with or without dashes: 28 Crockford base32 characters.
            Pattern.compile("\\b[0-9A-HJKMNP-TV-Za-hjkmnp-tv-z]{4}(?:-?[0-9A-HJKMNP-TV-Za-hjkmnp-tv-z]{4}){6}\\b"),
            // 128-bit keys and longer hex runs (MACs, encrypted tap data, key material).
            Pattern.compile("\\b[0-9A-Fa-f]{32,}\\b"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"));

    /** Settings and headers whose value is hidden: name=value or name: value. */
    private static final Pattern NAMED = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])([A-Za-z0-9_.-]*(?:key|token|password|secret))(=|:\\s*)([^\\s&,;\"']+)");
    /** Tap link parameters whose value is hidden: only name=value, as in a query string. */
    private static final Pattern TAP_PARAM = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(e|c|cmac|picc)(=)([^\\s&,;\"']+)");

    private Redact() {}

    /** {@code text} with anything that looks like a secret replaced by {@code [hidden]}. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = hideValues(NAMED, hideValues(TAP_PARAM, text));
        for (Pattern p : WHOLE) {
            out = p.matcher(out).replaceAll(HIDDEN);
        }
        return out;
    }

    private static String hideValues(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + HIDDEN));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
