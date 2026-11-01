package io.knura.knurd;

import io.knura.core.admin.PrivateFiles;
import io.knura.core.backup.BackupFile;
import io.knura.core.backup.BackupRecord;
import io.knura.core.backup.SettingsFolder;
import io.knura.core.backup.SheetPage;
import io.knura.core.resolver.OwnerKey;
import io.knura.core.resolver.ResolverClient;
import io.knura.knurgn.Knurgn;
import io.knura.knurgn.KnurgnConfig;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The settings the setup page may change, kept in the same files people edit by hand, so the
 * page and a text editor always agree:
 *
 * <ul>
 *   <li>{@code knura.name} in {@code knurd.properties}: what this Knura is called.
 *   <li>{@code knurd-tags.properties}: each tag's label and icon ({@code <UID>.label}, {@code
 *       <UID>.icon}). Labels are free text, unlike knurgN's knot names.
 *   <li>The knurgN actions file ({@code actions.file}, made as {@code knurgn.properties} the first
 *       time it is needed) and its owner-only secrets file: connectors and which tags use them.
 * </ul>
 *
 * <p>Every change to the actions files is checked by loading them as knurd would at start-up; if
 * that fails the files are put back as they were and the error is returned, so the page can never
 * leave knurd with a config it won't start with. A good change is put into use straight away
 * through {@link LiveActions}.
 */
final class HomeSettings {
    static final String LABELS_FILE = "knurd-tags.properties";
    static final String DEFAULT_ACTIONS_FILE = "knurgn.properties";
    static final String DEFAULT_SECRETS_FILE = "knurgn-secrets.properties";
    static final Set<String> TYPES = Set.of("webhook", "homeassistant", "matter");
    static final int MAX_LABEL = 40;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern UID = Pattern.compile("[0-9A-F]{14}");
    private static final Pattern ICON = Pattern.compile("[a-z0-9-]{1,24}");

    /** A tag's label and icon; either may be null. */
    record Label(String label, String icon) {}

    /** A connector, without its secret. */
    record Connection(String type, String id, String url, List<String> usedBy) {}

    private final Path knurdFile;
    private final Path dir;
    private final LiveActions live;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private Path actionsFile;
    private volatile Map<String, Label> labels;

    /**
     * @param actionsFile knurgN's file from {@code actions.file}, or null when it isn't set yet
     */
    HomeSettings(Path knurdFile, Path actionsFile, LiveActions live, Clock clock) throws IOException {
        this.knurdFile = knurdFile.toAbsolutePath();
        this.dir = this.knurdFile.getParent();
        this.actionsFile = actionsFile;
        this.live = live;
        this.clock = clock;
        this.labels = readLabels();
        live.labels(this::labelOf);
    }

    /** The tag's label, for tap events; fast, as it runs on every tap. */
    String labelOf(String uid) {
        Label l = labels.get(uid);
        return l == null ? null : l.label();
    }

    // ---- name ----

    synchronized String name() throws IOException {
        String name = load(knurdFile).getProperty("knura.name");
        return name == null || name.isBlank() ? null : name.trim();
    }

    synchronized void setName(String name) throws IOException {
        String problem = textProblem(name, MAX_LABEL);
        if (problem != null) {
            throw new IllegalArgumentException("name: " + problem);
        }
        rewrite(knurdFile, PropertiesText.set(read(knurdFile), "knura.name", name.trim()));
    }

    // ---- the address phones use ----

    /** public.url as saved now; knurd itself keeps using the one it started with until a restart. */
    synchronized String publicUrl() throws IOException {
        return KnurdConfig.publicUrl(load(knurdFile).getProperty("public.url", "").trim());
    }

    synchronized void setPublicUrl(String url) throws IOException {
        String clean = KnurdConfig.publicUrl(url == null ? "" : url.trim());
        if (clean == null) {
            throw new IllegalArgumentException("publicUrl is missing");
        }
        if (clean.length() > 200) {
            throw new IllegalArgumentException("publicUrl is too long");
        }
        rewrite(knurdFile, PropertiesText.set(read(knurdFile), "public.url", clean));
    }

    // ---- tag labels ----

    Map<String, Label> labels() {
        return labels;
    }

    synchronized void setLabel(String uid, String label, String icon) throws IOException {
        uid = uid(uid);
        if (label != null && textProblem(label, MAX_LABEL) != null) {
            throw new IllegalArgumentException("label: " + textProblem(label, MAX_LABEL));
        }
        if (icon != null && !ICON.matcher(icon).matches()) {
            throw new IllegalArgumentException("icon: use 1 to 24 lowercase letters, digits or '-'");
        }
        Path file = dir.resolve(LABELS_FILE);
        String text = Files.exists(file) ? read(file) : "# Tag labels and icons, set on the setup page.\n";
        text = label == null ? PropertiesText.remove(text, uid + ".label") : PropertiesText.set(text, uid + ".label", label.trim());
        text = icon == null ? PropertiesText.remove(text, uid + ".icon") : PropertiesText.set(text, uid + ".icon", icon);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        labels = readLabels();
    }

    private Map<String, Label> readLabels() throws IOException {
        Path file = dir.resolve(LABELS_FILE);
        if (!Files.exists(file)) {
            return Map.of();
        }
        Properties p = load(file);
        Map<String, Label> out = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot != 14 || !UID.matcher(key.substring(0, dot).toUpperCase(Locale.ROOT)).matches()) {
                continue;
            }
            String u = key.substring(0, dot).toUpperCase(Locale.ROOT);
            out.computeIfAbsent(u, k -> new Label(clean(p.getProperty(k + ".label")), clean(p.getProperty(k + ".icon"))));
        }
        return Map.copyOf(out);
    }

    // ---- actions ----

    /** Which connectors each tag runs, by UID. */
    synchronized Map<String, List<String>> tagActions() throws IOException {
        Map<String, List<String>> out = new TreeMap<>();
        if (actionsFile == null || !Files.exists(actionsFile)) {
            return out;
        }
        Properties rules = load(actionsFile);
        for (String key : rules.stringPropertyNames()) {
            String[] parts = key.split("\\.");
            if (parts.length == 3 && parts[0].equals("knot") && parts[2].equals("actions")) {
                out.put(parts[1].toUpperCase(Locale.ROOT), split(rules.getProperty(key)));
            }
        }
        return out;
    }

    synchronized List<Connection> connections() throws IOException {
        List<Connection> out = new ArrayList<>();
        if (actionsFile == null || !Files.exists(actionsFile)) {
            return out;
        }
        Properties rules = load(actionsFile);
        Map<String, List<String>> uses = tagActions();
        for (String key : new TreeSet<>(rules.stringPropertyNames())) {
            String[] parts = key.split("\\.");
            if (parts.length == 3 && TYPES.contains(parts[0]) && parts[2].equals("url")) {
                List<String> by = new ArrayList<>();
                uses.forEach((uid, ids) -> {
                    if (ids.contains(parts[1])) {
                        by.add(uid);
                    }
                });
                out.add(new Connection(parts[0], parts[1], rules.getProperty(key).trim(), by));
            }
        }
        return out;
    }

    /**
     * Adds a connector or changes its address. A new one gets a fresh secret (a whsec_ signing
     * secret, or a Home Assistant webhook id), returned here once and never again; changing the
     * address of an existing one keeps its secret, and returns empty.
     */
    /**
     * Gives a connector a fresh secret (a new Home Assistant webhook id, or signing secret), for when
     * the old one is lost. The old one stops working at once. Returns the new one, shown only now.
     */
    synchronized String newConnectionSecret(String type, String id) throws IOException {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be webhook, homeassistant or matter");
        }
        id(id);
        if (actionsFile == null || !Files.exists(actionsFile)
                || load(actionsFile).getProperty(type + "." + id + ".url") == null) {
            throw new NotFound("no " + type + " connector named " + id);
        }
        Properties rules = load(actionsFile);
        Path secretsFile = secretsFile(rules);
        String secrets = Files.exists(secretsFile) ? read(secretsFile) : "";
        String secret = newSecret(type);
        change(read(actionsFile), secretsFile, PropertiesText.set(secrets, id, secret));
        return secret;
    }

    synchronized Optional<String> putConnection(String type, String id, String url) throws IOException {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be webhook, homeassistant or matter");
        }
        id(id);
        if (url == null || url.isBlank() || url.length() > 2048) {
            throw new IllegalArgumentException("url: give the address to send to");
        }
        ensureActionsFile();
        Properties rules = load(actionsFile);
        for (String other : TYPES) {
            if (!other.equals(type) && rules.getProperty(other + "." + id + ".url") != null) {
                throw new IllegalArgumentException("there is already a " + other + " connector named " + id);
            }
        }
        Path secretsFile = secretsFile(rules);
        String secrets = Files.exists(secretsFile) ? read(secretsFile) : "";
        Properties secretProps = new Properties();
        secretProps.load(new StringReader(secrets));
        String secret = null;
        if (secretProps.getProperty(id) == null) {
            secret = newSecret(type);
            secrets = PropertiesText.set(secrets, id, secret);
        }
        String rulesText = PropertiesText.set(read(actionsFile), type + "." + id + ".url", url.trim());
        change(rulesText, secretsFile, secrets);
        return Optional.ofNullable(secret);
    }

    /** Removes a connector and its secret. Refused while a tag still uses it. */
    synchronized void removeConnection(String type, String id) throws IOException {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be webhook, homeassistant or matter");
        }
        id(id);
        if (actionsFile == null || !Files.exists(actionsFile) || load(actionsFile).getProperty(type + "." + id + ".url") == null) {
            throw new NotFound("no " + type + " connector named " + id);
        }
        for (Map.Entry<String, List<String>> e : tagActions().entrySet()) {
            if (e.getValue().contains(id)) {
                throw new InUse("tag " + e.getKey() + " still uses " + id + "; take it off that tag first");
            }
        }
        Properties rules = load(actionsFile);
        Path secretsFile = secretsFile(rules);
        String secrets = Files.exists(secretsFile) ? PropertiesText.remove(read(secretsFile), id) : "";
        change(PropertiesText.remove(read(actionsFile), type + "." + id + ".url"), secretsFile, secrets);
    }

    /** Sets which connectors a tag runs, in order. An empty list takes the tag's rule away. */
    synchronized void setTagActions(String uid, List<String> ids) throws IOException {
        uid = uid(uid);
        for (String id : ids) {
            id(id);
        }
        if (ids.isEmpty() && (actionsFile == null || !Files.exists(actionsFile))) {
            return;
        }
        ensureActionsFile();
        Properties rules = load(actionsFile);
        Path secretsFile = secretsFile(rules);
        String text = read(actionsFile);
        if (ids.isEmpty()) {
            text = PropertiesText.remove(PropertiesText.remove(text, "knot." + uid + ".actions"), "knot." + uid + ".name");
        } else {
            text = PropertiesText.set(text, "knot." + uid + ".actions", String.join(",", ids));
        }
        change(text, secretsFile, Files.exists(secretsFile) ? read(secretsFile) : null);
    }

    /** Reads the actions files again after someone edited them by hand. */
    synchronized int reload() throws IOException {
        if (actionsFile == null) {
            return 0;
        }
        if (!Files.exists(actionsFile)) {
            throw new IllegalArgumentException("the actions file " + actionsFile.getFileName() + " is missing");
        }
        Knurgn next = KnurgnConfig.load(actionsFile, clock);
        live.replace(next);
        return next.knotCount();
    }

    /** Runs a tag's actions now, as "Send a test" on the setup page; the number started. */
    int test(String uid) {
        return live.test(uid(uid));
    }

    int knotCount() {
        return live.knotCount();
    }


    // ---- backups ----

    /**
     * A passphrase-locked backup of the settings folder (as {@code knurctl backup create} makes),
     * recorded as this master key's latest backup file.
     */
    synchronized byte[] backupFile(char[] passphrase) throws IOException {
        if (passphrase.length < BackupFile.MIN_PASSPHRASE) {
            throw new IllegalArgumentException("passphrase: use at least " + BackupFile.MIN_PASSPHRASE + " characters");
        }
        byte[] master = masterKey();
        SettingsFolder.Collected collected = SettingsFolder.collect(dir);
        java.time.Instant now = clock.instant();
        byte[] sealed;
        try {
            sealed = BackupFile.seal(collected.files(), now, passphrase, random);
        } finally {
            collected.files().values().forEach(b -> java.util.Arrays.fill(b, (byte) 0));
        }
        BackupRecord.record(dir, BackupRecord.keyId(master), BackupRecord.Kind.FILE, now);
        java.util.Arrays.fill(master, (byte) 0);
        return sealed;
    }

    /** The printable recovery sheet (as {@code knurctl backup sheet} makes), recorded as made. */
    synchronized String recoverySheet() throws IOException {
        Properties p = load(knurdFile);
        byte[] master = masterKey();
        String id = BackupRecord.keyId(master);
        String owner = p.getProperty("resolver.owner", "").trim();
        byte[] seed = null;
        if (!owner.isEmpty() && !p.getProperty("resolver.key", "").isBlank()) {
            seed = io.knura.core.crypto.Hex.decode(OwnerKey.load(p.getProperty("resolver.key").trim(),
                    p.getProperty("resolver.publicKey", "").trim()).seedHex());
        }
        java.time.Instant now = clock.instant();
        String html = SheetPage.render(id, now.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(), master,
                owner.isEmpty() ? null : owner, p.getProperty("resolver.url", ResolverClient.DEFAULT_URL).trim(), seed);
        java.util.Arrays.fill(master, (byte) 0);
        if (seed != null) {
            java.util.Arrays.fill(seed, (byte) 0);
        }
        BackupRecord.record(dir, id, BackupRecord.Kind.SHEET, now);
        return html;
    }

    private byte[] masterKey() throws IOException {
        byte[] master = KnurdConfig.masterKey(load(knurdFile), dir);
        if (master == null) {
            throw new IllegalArgumentException("this knurd has no master.key, so there is no key to back up");
        }
        return master;
    }

    /** The phones allowed to set tags up: the names in admin.provision.tokens. */
    synchronized List<String> phones() throws IOException {
        return split(load(knurdFile).getProperty("admin.provision.tokens"));
    }

    /** Adds a phone to admin.provision.tokens, so it may set tags up after a restart too. */
    synchronized void addPhone(String name) throws IOException {
        List<String> names = new java.util.ArrayList<>(split(load(knurdFile).getProperty("admin.provision.tokens")));
        if (!names.contains(name)) {
            names.add(name);
            java.util.Collections.sort(names);
            rewrite(knurdFile, PropertiesText.set(read(knurdFile), "admin.provision.tokens", String.join(",", names)));
        }
    }

    /** admin.tls.keystore as saved, resolved against the settings folder, or null. */
    synchronized Path adminKeystore() throws IOException {
        String k = load(knurdFile).getProperty("admin.tls.keystore", "").trim();
        return k.isEmpty() ? null : dir.resolve(k);
    }

    /** admin.tls.password.file as saved, resolved against the settings folder, or null. */
    synchronized Path adminKeystorePassword() throws IOException {
        String k = load(knurdFile).getProperty("admin.tls.password.file", "").trim();
        return k.isEmpty() ? null : dir.resolve(k);
    }

    /** Saves the admin TLS key's files (names in the settings folder). */
    synchronized void setAdminKeystore(String keystore, String passwordFile) throws IOException {
        String text = PropertiesText.set(read(knurdFile), "admin.tls.keystore", keystore);
        rewrite(knurdFile, PropertiesText.set(text, "admin.tls.password.file", passwordFile));
    }

    /** Saves admin.lan.port, the Wi-Fi listener's port. */
    synchronized void setLanPort(int port) throws IOException {
        rewrite(knurdFile, PropertiesText.set(read(knurdFile), "admin.lan.port", String.valueOf(port)));
    }

    /** The settings folder. */
    Path folder() {
        return dir;
    }

    // ---- helpers ----

    /** The first change through the setup page makes knurgn.properties and points knurd at it. */
    private void ensureActionsFile() throws IOException {
        if (actionsFile != null) {
            if (!Files.exists(actionsFile)) {
                Files.writeString(actionsFile, "secrets.file=" + DEFAULT_SECRETS_FILE + "\n", StandardCharsets.UTF_8);
            }
            return;
        }
        Path file = dir.resolve(DEFAULT_ACTIONS_FILE);
        if (!Files.exists(file)) {
            Files.writeString(file, "# What each tag does. The setup page changes this file; you may edit it too.\n"
                    + "secrets.file=" + DEFAULT_SECRETS_FILE + "\n", StandardCharsets.UTF_8);
        }
        rewrite(knurdFile, PropertiesText.set(read(knurdFile), "actions.file", DEFAULT_ACTIONS_FILE));
        actionsFile = file;
    }

    /**
     * Writes the actions file and secrets file, then loads them as knurd would. If that fails,
     * puts both back and throws, so a bad change never sticks.
     *
     * @param secrets the secrets file's new text, or null to leave it alone
     */
    private void change(String rulesText, Path secretsFile, String secrets) throws IOException {
        String oldRules = read(actionsFile);
        String oldSecrets = Files.exists(secretsFile) ? read(secretsFile) : null;
        Files.writeString(actionsFile, rulesText, StandardCharsets.UTF_8);
        if (secrets != null) {
            PrivateFiles.replace(secretsFile, secrets);
        }
        Knurgn next;
        try {
            next = KnurgnConfig.load(actionsFile, clock);
        } catch (IllegalArgumentException | IOException e) {
            Files.writeString(actionsFile, oldRules, StandardCharsets.UTF_8);
            if (secrets != null) {
                if (oldSecrets == null) {
                    Files.deleteIfExists(secretsFile);
                } else {
                    PrivateFiles.replace(secretsFile, oldSecrets);
                }
            }
            throw e;
        }
        live.replace(next);
    }

    private Path secretsFile(Properties rules) {
        return actionsFile.toAbsolutePath().getParent()
                .resolve(rules.getProperty("secrets.file", DEFAULT_SECRETS_FILE).trim());
    }

    private String newSecret(String type) {
        if (type.equals("homeassistant")) {
            byte[] b = new byte[36];
            random.nextBytes(b);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b); // 48 characters
        }
        byte[] b = new byte[32];
        random.nextBytes(b);
        return "whsec_" + Base64.getEncoder().encodeToString(b);
    }

    /** knurd.properties holds the master key, so it stays owner-only. */
    private static void rewrite(Path file, String text) throws IOException {
        PrivateFiles.replace(file, text);
    }

    static String uid(String uid) {
        String u = uid == null ? "" : uid.toUpperCase(Locale.ROOT);
        if (!UID.matcher(u).matches()) {
            throw new NotFound("a tag UID is 14 hex digits, like 042D6B02936980");
        }
        return u;
    }

    static String id(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a connector name uses 1 to 64 letters, digits, '.', '_' or '-'");
        }
        return id;
    }

    /** Why some free text can't be used as a name or label, or null. */
    static String textProblem(String s, int max) {
        if (s == null || s.isBlank()) {
            return "it can't be empty";
        }
        if (s.trim().length() > max) {
            return "use at most " + max + " characters";
        }
        if (s.chars().anyMatch(Character::isISOControl)) {
            return "it can't have line breaks or control characters";
        }
        return null;
    }

    static List<String> split(String list) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (String s : list.split(",")) {
            if (!s.isBlank() && !out.contains(s.trim())) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(read(file)));
        return p;
    }

    /** A tag or connector that doesn't exist: 404. */
    static final class NotFound extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }

    /** A connector a tag still uses: 409. */
    static final class InUse extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InUse(String message) {
            super(message);
        }
    }
}
