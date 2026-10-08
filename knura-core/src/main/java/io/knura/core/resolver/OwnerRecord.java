package io.knura.core.resolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a tap resolver sends an owner's taps: {@code <server>/tap}. The owner signs
 * {@link #signedText} with their {@link OwnerKey}; the resolver checks that signature, keeps the
 * record, and serves it back so knurd and knurctl can check it again themselves. {@code seq} must
 * go up with every change so an old record can't be replayed. Must match
 * {@code knura-resolver/src/record.js}.
 */
public record OwnerRecord(String owner, String server, long seq, String publicKey, String signature) {
    public static final int MAX_SERVER_LENGTH = 200;
    /** Largest seq JavaScript holds exactly (2^53 - 1). */
    public static final long MAX_SEQ = 9007199254740991L;
    private static final Pattern HOST =
            Pattern.compile("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]");

    /** Signs a record for {@code server}. */
    public static OwnerRecord sign(String owner, String server, long seq, OwnerKey key) {
        byte[] signature = key.sign(signedText(owner, server, seq).getBytes(StandardCharsets.UTF_8));
        return new OwnerRecord(owner, server, seq, key.publicKeyHex(), HexFormat.of().formatHex(signature));
    }

    /** The exact text the owner signs. */
    public static String signedText(String owner, String server, long seq) {
        return "knura-resolver-v1\nowner=" + owner + "\nserver=" + server + "\nseq=" + seq + "\n";
    }

    /** True if the signature is good for this record and its public key. */
    public boolean verify() {
        try {
            return OwnerKey.verify(
                    HexFormat.of().parseHex(publicKey),
                    signedText(owner, server, seq).getBytes(StandardCharsets.UTF_8),
                    HexFormat.of().parseHex(signature));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The body of a register or move request: {@code PUT /api/v1/owners/<owner>}. */
    public String updateJson() {
        return "{\"server\":\"" + server + "\",\"seq\":" + seq + ",\"publicKey\":\"" + publicKey
                + "\",\"signature\":\"" + signature + "\"}";
    }

    /**
     * Reads a record as the resolver serves it. Throws {@link IllegalArgumentException} if a field
     * is missing or has the wrong type.
     */
    public static OwnerRecord fromJson(String json) {
        Map<String, Object> fields = FlatJson.parse(json);
        return new OwnerRecord(
                string(fields, "owner"),
                string(fields, "server"),
                number(fields, "seq"),
                string(fields, "publicKey"),
                string(fields, "signature"));
    }

    /**
     * Checks a server address the way the resolver does. Returns null if it is fine, else the
     * reason. Only https with a host name (no IP address), no user name, query or fragment, and no
     * trailing slash.
     *
     * @param allowLoopbackHttp accept http://127.0.0.1 too, for a resolver running on this computer
     */
    public static String checkServer(String server, boolean allowLoopbackHttp) {
        if (server == null || server.isEmpty() || server.length() > MAX_SERVER_LENGTH) {
            return "server must be 1 to " + MAX_SERVER_LENGTH + " characters";
        }
        if (!server.chars().allMatch(ch -> ch > 0x20 && ch < 0x7F)) {
            return "server must be plain ASCII with no spaces";
        }
        if (server.endsWith("/")) {
            return "server must not end with /";
        }
        URI uri;
        try {
            uri = new URI(server);
        } catch (URISyntaxException e) {
            return "server is not a valid URL";
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "server must not have a user name, ?query or #fragment";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (allowLoopbackHttp && scheme.equals("http") && isLoopback(host)) {
            return null;
        }
        if (!scheme.equals("https")) {
            return "server must start with https://";
        }
        if (uri.getPort() == 443 || !host.equals(host.toLowerCase(Locale.ROOT))) {
            return "server must be written in its plain form, e.g. https://host.example.net";
        }
        if (!HOST.matcher(host).matches()) {
            return "server must use a host name such as your-computer.your-tailnet.ts.net, not an IP address";
        }
        if (host.endsWith(".localhost") || host.endsWith(".local")) {
            return "server must be reachable from the internet";
        }
        return null;
    }

    public static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }

    private static String string(Map<String, Object> fields, String name) {
        if (fields.get(name) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("record has no text field " + name);
    }

    private static long number(Map<String, Object> fields, String name) {
        if (fields.get(name) instanceof Long n) {
            return n;
        }
        throw new IllegalArgumentException("record has no number field " + name);
    }
}
