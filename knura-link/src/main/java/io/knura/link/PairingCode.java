package io.knura.link;

import io.knura.core.resolver.OwnerIds;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The code a hosted Knura account's dashboard shows when you add a home link:
 *
 * <pre>
 * knura://link?url=wss%3A%2F%2Fexample.com%2Flink&amp;owner=ab3cd4ef5g&amp;token=knlink_...
 * </pre>
 *
 * The token is the home link's login. It's shown once, so it is only ever pasted into {@code
 * knura-link pair} and saved to a file only its owner can read.
 */
record PairingCode(URI url, String owner, String token) {
    static final Pattern TOKEN = Pattern.compile("knlink_[A-Za-z0-9_-]{43}");
    private static final String PREFIX = "knura://link?";

    PairingCode {
        checkUrl(url);
        if (!OwnerIds.isValid(owner)) {
            throw new IllegalArgumentException("the owner ID isn't valid");
        }
        if (!TOKEN.matcher(token).matches()) {
            // Never echo the token: it's a secret even when it's malformed.
            throw new IllegalArgumentException("the token isn't a home link token");
        }
    }

    /** Throws {@link IllegalArgumentException} with a message that never quotes the code. */
    static PairingCode parse(String code) {
        String text = code == null ? "" : code.strip();
        if (!text.startsWith(PREFIX)) {
            throw new IllegalArgumentException("that isn't a home link pairing code; it starts with " + PREFIX);
        }
        Map<String, String> fields = new HashMap<>();
        for (String pair : text.substring(PREFIX.length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("the pairing code is cut off or damaged; copy it again");
            }
            String key = pair.substring(0, eq);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (fields.put(key, value) != null) {
                throw new IllegalArgumentException("the pairing code has " + key + " twice");
            }
        }
        for (String key : new String[] {"url", "owner", "token"}) {
            if (!fields.containsKey(key)) {
                throw new IllegalArgumentException("the pairing code has no " + key + "; copy it again");
            }
        }
        URI url;
        try {
            url = new URI(fields.get("url"));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("the pairing code's url isn't a valid address");
        }
        return new PairingCode(url, fields.get("owner"), fields.get("token"));
    }

    /**
     * Only {@code wss://}, so the token and taps travel encrypted. Plain {@code ws://} is allowed
     * to this computer only, for testing.
     */
    static void checkUrl(URI url) {
        String scheme = url.getScheme();
        String host = url.getHost();
        boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
        if (host == null || !("wss".equals(scheme) || ("ws".equals(scheme) && local))) {
            throw new IllegalArgumentException("the home link address must start with wss://");
        }
        if (url.getRawQuery() != null || url.getRawFragment() != null || url.getRawUserInfo() != null) {
            throw new IllegalArgumentException("the home link address can't have a query, fragment or user name");
        }
    }

    @Override
    public String toString() {
        // Keep the token out of logs and stack traces.
        return "PairingCode[url=" + url + ", owner=" + owner + "]";
    }
}
