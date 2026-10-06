package io.knura.knurgn;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads knurgN's rules and connectors from a properties file, and connector secrets from a second
 * file that only its owner may read:
 *
 * <pre>
 * # knurgn.properties
 * knot.042D6B02936980.name=desk-candle
 * knot.042D6B02936980.actions=desk-webhook
 * webhook.desk-webhook.url=http://127.0.0.1:8425/knura
 * secrets.file=knurgn-secrets.properties
 *
 * # knurgn-secrets.properties (chmod 600)
 * desk-webhook=whsec_...
 * </pre>
 *
 * Names and ids may use letters, digits, {@code .}, {@code _} and {@code -}. Unknown settings are
 * errors, so a typo can't silently switch a rule off.
 */
public final class KnurgnConfig {
    static final String DEFAULT_SECRETS_FILE = "knurgn-secrets.properties";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern UID = Pattern.compile("[0-9A-Fa-f]{14}");
    private static final Set<PosixFilePermission> NOT_OWNER = EnumSet.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private KnurgnConfig() {}

    /** Loads the config and its secrets file and builds the engine. */
    public static Knurgn load(Path file, Clock clock) throws IOException {
        Properties rules = read(file);
        Path dir = file.toAbsolutePath().getParent();
        Path secretsFile = dir.resolve(rules.getProperty("secrets.file", DEFAULT_SECRETS_FILE).trim());
        Properties secrets = Files.exists(secretsFile) ? readSecrets(secretsFile) : new Properties();
        return new Knurgn(knots(rules, secrets, secretsFile.getFileName().toString(), clock), clock);
    }

    /** Builds the knots; {@code secretsName} is only used in error messages. */
    static List<Knot> knots(Properties rules, Properties secrets, String secretsName, Clock clock) {
        Map<String, Connector> connectors = new HashMap<>();
        Map<String, String> knotNames = new HashMap<>();
        Map<String, String> knotActions = new HashMap<>();
        for (String key : new TreeSet<>(rules.stringPropertyNames())) {
            String value = rules.getProperty(key).trim();
            String[] parts = key.split("\\.");
            if (key.equals("secrets.file")) {
                continue;
            } else if (parts.length == 3 && parts[0].equals("knot") && parts[2].equals("name")) {
                knotNames.put(uid(parts[1]), name(key, value));
            } else if (parts.length == 3 && parts[0].equals("knot") && parts[2].equals("actions")) {
                knotActions.put(uid(parts[1]), value);
            } else if (parts.length == 3 && parts[0].equals("webhook") && parts[2].equals("url")) {
                String id = name(key, parts[1]);
                String secret = secrets.getProperty(id);
                if (secret == null) {
                    throw new IllegalArgumentException("no secret for webhook " + id + ": add " + id
                            + "=whsec_... to " + secretsName);
                }
                connectors.put(id, new WebhookConnector(
                        id, uri(key, value), WebhookSignature.parseSecret(id, secret), clock));
            } else if (key.endsWith(".secret")) {
                throw new IllegalArgumentException(
                        key + ": secrets don't belong in this file; put them in " + secretsName);
            } else {
                throw new IllegalArgumentException("unknown setting " + key);
            }
        }
        for (String id : secrets.stringPropertyNames()) {
            if (!connectors.containsKey(id)) {
                throw new IllegalArgumentException(secretsName + " has a secret for " + id
                        + ", but there is no webhook." + id + ".url");
            }
        }
        for (String uid : knotNames.keySet()) {
            if (!knotActions.containsKey(uid)) {
                throw new IllegalArgumentException("knot." + uid + ".actions is missing");
            }
        }

        List<Knot> knots = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (Map.Entry<String, String> entry : knotActions.entrySet()) {
            String uid = entry.getKey();
            String name = knotNames.getOrDefault(uid, uid);
            if (!usedNames.add(name)) {
                throw new IllegalArgumentException("two knots are named " + name);
            }
            List<Connector> actions = new ArrayList<>();
            for (String id : entry.getValue().split(",")) {
                Connector connector = connectors.get(id.trim());
                if (connector == null) {
                    throw new IllegalArgumentException(
                            "knot." + uid + ".actions: no connector named \"" + id.trim() + "\"");
                }
                if (actions.contains(connector)) {
                    throw new IllegalArgumentException("knot." + uid + ".actions lists " + id.trim() + " twice");
                }
                actions.add(connector);
            }
            knots.add(new Knot(uid, name, actions));
        }
        return knots;
    }

    private static String uid(String text) {
        if (!UID.matcher(text).matches()) {
            throw new IllegalArgumentException("knot." + text + ": a tag UID is 14 hex digits, like 042D6B02936980");
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private static String name(String key, String value) {
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    key + ": use 1 to 64 letters, digits, '.', '_' or '-' (got \"" + value + "\")");
        }
        return value;
    }

    private static URI uri(String key, String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(key + " is not a valid address", e);
        }
    }

    private static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    /** Reads the secrets file, refusing it if anyone but its owner can read or change it. */
    static Properties readSecrets(Path file) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view != null) {
            Set<PosixFilePermission> perms = EnumSet.copyOf(view.readAttributes().permissions());
            perms.retainAll(NOT_OWNER);
            if (!perms.isEmpty()) {
                throw new IllegalArgumentException(file + " can be read or changed by other users; run: chmod 600 " + file);
            }
        }
        return read(file);
    }
}
