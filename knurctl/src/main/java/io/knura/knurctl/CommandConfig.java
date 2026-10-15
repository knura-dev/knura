package io.knura.knurctl;

import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.CertFingerprint;
import io.knura.core.admin.LocalAddresses;
import io.knura.core.admin.PrivateFiles;
import io.knura.knurctl.Main.UsageException;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The file {@code --command-config} names (like Kafka's): where knurd's admin API is and the
 * token to log in with. knurctl refuses the file unless only its owner can read it (chmod 600),
 * and refuses a plain http URL unless it is this computer, so the token never crosses a network
 * in clear.
 *
 * <pre>
 * knurd.url=https://192.168.1.20:8425
 * knurd.token=knurd_...
 * knurd.fingerprint=...   (SHA-256 of knurd's admin certificate; optional for a bought certificate)
 * </pre>
 */
record CommandConfig(URI url, String token, String fingerprint) {
    static final String DEFAULT_FILE = "knurctl-admin.properties";
    private static final Set<String> KEYS = Set.of("knurd.url", "knurd.token", "knurd.fingerprint");

    static CommandConfig load(Path file) throws IOException, UsageException {
        if (!Files.exists(file)) {
            throw new UsageException("no command config " + file.toAbsolutePath()
                    + "; make one on knurd's computer with: knurctl admin token add <name>");
        }
        String problem = PrivateFiles.problem(file);
        if (problem != null) {
            throw new UsageException(problem + " (it holds your admin token)");
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        for (String name : p.stringPropertyNames()) {
            if (!KEYS.contains(name)) {
                throw new UsageException("unknown setting " + name + " in " + file + "; expected " + String.join(", ", KEYS));
            }
        }
        URI url;
        try {
            url = checkUrl(p.getProperty("knurd.url", "").trim());
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + " (knurd.url in " + file + ")");
        }
        String token = p.getProperty("knurd.token", "").trim();
        if (!AdminTokens.isWellFormed(token)) {
            throw new UsageException("knurd.token in " + file + " is missing or isn't a knurd token");
        }
        String fingerprint = p.getProperty("knurd.fingerprint", "").trim();
        if (fingerprint.isEmpty()) {
            fingerprint = null;
        } else {
            if (!url.getScheme().equals("https")) {
                throw new UsageException("knurd.fingerprint only applies to an https knurd.url (in " + file + ")");
            }
            try {
                fingerprint = CertFingerprint.normalize(fingerprint);
            } catch (IllegalArgumentException e) {
                throw new UsageException("knurd.fingerprint in " + file + ": " + e.getMessage());
            }
        }
        return new CommandConfig(url, token, fingerprint);
    }

    /**
     * Checks an admin API address: https, or http only for this computer; just scheme, host and
     * port. Returns it without a trailing slash.
     */
    static URI checkUrl(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("knurd's admin URL is not set");
        }
        URI uri;
        try {
            uri = new URI(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid URL: " + value, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("the admin URL must look like https://192.168.1.20:8425: " + value);
        }
        if (scheme.equals("http") && !LocalAddresses.isLoopbackName(uri.getHost())) {
            throw new IllegalArgumentException("the admin URL must be https unless it is this computer"
                    + " (127.0.0.1 or localhost), so the token isn't sent in clear: " + value);
        }
        return uri;
    }

    /** The text of a command config file. */
    static String format(URI url, String token, String fingerprint, String tokenName) {
        return """
                # knurctl admin settings: knurd's admin API and the token "%s" to log in with.
                # This file is a password. Keep it chmod 600, never commit or share it. If it leaks,
                # run on knurd's computer: knurctl admin token remove %s
                # Use it with: knurctl admin status --command-config <this file>
                knurd.url=%s
                knurd.token=%s
                """.formatted(tokenName, tokenName, url, token)
                + (fingerprint == null ? "" : "# SHA-256 of knurd's admin certificate; knurctl trusts only that certificate.\n"
                        + "knurd.fingerprint=" + fingerprint + "\n");
    }
}
