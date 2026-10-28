package io.knura.link;

import io.knura.core.log.Logs;
import io.knura.core.resolver.OwnerIds;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The home link's settings, written by {@code knura-link pair}:
 *
 * <pre>
 * # link.properties
 * url=wss://example.com/link
 * owner=ab3cd4ef5g
 * secrets.file=link-secrets.properties
 * actions.file=knurgn.properties
 * # optional: which connectors the dashboard may choose for a tag (default: all; none: off)
 * dashboard.connectors=living-room
 * # optional: how much to log, and a log file besides standard error (see Logs)
 * log.level=debug
 * log.file=logs/knura-link.log
 *
 * # link-secrets.properties (chmod 600)
 * token=knlink_...
 * </pre>
 *
 * {@code actions.file} is the same knurgN config knurd uses: knot rules and connectors. Relative
 * paths are read from the folder that holds {@code link.properties}. Unknown settings are errors.
 */
record LinkConfig(URI url, String owner, String token, Path actionsFile, Set<String> dashboardConnectors) {
    /** {@code dashboardConnectors} is null for all of knurgN's connectors. */
    LinkConfig(URI url, String owner, String token, Path actionsFile) {
        this(url, owner, token, actionsFile, null);
    }

    static final String DEFAULT_SECRETS_FILE = "link-secrets.properties";
    static final String DEFAULT_ACTIONS_FILE = "knurgn.properties";

    private static final Set<String> SETTINGS = Set.of("url", "owner", "secrets.file", "actions.file", "dashboard.connectors",
            Logs.LEVEL_SETTING, Logs.FILE_SETTING);
    private static final Set<PosixFilePermission> NOT_OWNER = EnumSet.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    static LinkConfig load(Path file) throws IOException {
        Properties settings = read(file);
        for (String key : settings.stringPropertyNames()) {
            if (!SETTINGS.contains(key)) {
                throw new IllegalArgumentException("unknown setting " + key + " in " + file.getFileName());
            }
        }
        Path dir = file.toAbsolutePath().getParent();
        URI url;
        try {
            url = new URI(required(settings, "url", file));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url in " + file.getFileName() + " isn't a valid address");
        }
        PairingCode.checkUrl(url);
        String owner = required(settings, "owner", file);
        if (!OwnerIds.isValid(owner)) {
            throw new IllegalArgumentException("owner in " + file.getFileName() + " isn't a valid owner ID");
        }
        Path secretsFile = dir.resolve(settings.getProperty("secrets.file", DEFAULT_SECRETS_FILE).trim());
        if (!Files.exists(secretsFile)) {
            throw new IllegalArgumentException(secretsFile + " not found; run knura-link pair");
        }
        String token = readSecrets(secretsFile).getProperty("token", "").trim();
        if (!PairingCode.TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException(secretsFile.getFileName() + " needs a line token=knlink_...; run knura-link pair");
        }
        Path actions = dir.resolve(settings.getProperty("actions.file", DEFAULT_ACTIONS_FILE).trim());
        return new LinkConfig(url, owner, token, actions, dashboardConnectors(settings.getProperty("dashboard.connectors")));
    }

    private static Set<String> dashboardConnectors(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.strip().equals("none")) {
            return Set.of();
        }
        Set<String> ids = new TreeSet<>();
        for (String id : value.split(",")) {
            if (!TapRelay.CONNECTOR.matcher(id.strip()).matches()) {
                throw new IllegalArgumentException("dashboard.connectors: list connector ids from knurgn.properties,"
                        + " separated by commas, or none");
            }
            ids.add(id.strip());
        }
        return ids;
    }

    /**
     * Saves a pairing: {@code link.properties} plus a secrets file only this user can read. Refuses
     * to overwrite an existing pairing, so pairing twice can't silently drop the old one.
     */
    static void save(Path file, PairingCode code) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path secretsFile = dir.resolve(DEFAULT_SECRETS_FILE);
        for (Path existing : new Path[] {file, secretsFile}) {
            if (Files.exists(existing)) {
                throw new IllegalArgumentException(existing + " already exists; delete it first to pair again");
            }
        }
        Properties secrets = new Properties();
        secrets.setProperty("token", code.token());
        if (Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.createFile(secretsFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(secretsFile);
        }
        try (Writer out = Files.newBufferedWriter(secretsFile, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
            secrets.store(out, "Home link login. Keep this file private (chmod 600).");
        }
        Properties settings = new Properties();
        settings.setProperty("url", code.url().toString());
        settings.setProperty("owner", code.owner());
        settings.setProperty("secrets.file", DEFAULT_SECRETS_FILE);
        settings.setProperty("actions.file", DEFAULT_ACTIONS_FILE);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            settings.store(out, "Knura home link");
        }
    }

    private static String required(Properties settings, String key, Path file) {
        String value = settings.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(file.getFileName() + " has no " + key + "; run knura-link pair");
        }
        return value;
    }

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

    private static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    @Override
    public String toString() {
        // Keep the token out of logs and stack traces.
        return "LinkConfig[url=" + url + ", owner=" + owner + ", actionsFile=" + actionsFile
                + ", dashboardConnectors=" + (dashboardConnectors == null ? "all" : dashboardConnectors) + "]";
    }
}
