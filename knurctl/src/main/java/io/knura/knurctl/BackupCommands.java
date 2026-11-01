package io.knura.knurctl;

import io.knura.core.backup.BackupFile;
import io.knura.core.backup.BackupFile.BackupException;
import io.knura.core.backup.BackupRecord;
import io.knura.core.backup.RecoverySheet;
import io.knura.core.backup.RecoverySheet.SheetException;
import io.knura.core.crypto.Hex;
import io.knura.core.resolver.OwnerIds;
import io.knura.core.resolver.OwnerKey;
import io.knura.core.resolver.ResolverClient;
import io.knura.knurctl.Main.UsageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code knurctl backup}: keeps the master key (and the tap.knura.io key) safe. Every tag's keys
 * come from the master key, so losing it loses every tag; these commands make an encrypted copy of
 * the settings folder, a printable recovery sheet, and put either back.
 */
final class BackupCommands {
    /** Logs and old backups stay out of backups; anything bigger than this is not a setting. */
    private static final Pattern SKIP = Pattern.compile(".*(\\.log(\\.\\d+)?|\\.tmp|" + Pattern.quote(BackupFile.SUFFIX) + ")");
    private static final long MAX_FILE = 5L * 1024 * 1024;
    private static final int TRIES = 3;
    private static final int MASTER_BYTES = 16;
    private static final int RESOLVER_KEY_BYTES = 32;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final PrintStream out;
    private final Map<String, String> options;
    private final Path configPath;
    private final Prompter prompter;

    BackupCommands(PrintStream out, Map<String, String> options, Path configPath, Prompter prompter) {
        this.out = out;
        this.options = options;
        this.configPath = configPath;
        this.prompter = prompter;
    }

    void run(List<String> words) throws IOException, UsageException {
        String sub = words.size() > 1 ? words.get(1) : "status";
        String arg = words.size() > 2 ? words.get(2) : null;
        switch (sub) {
            case "status" -> status();
            case "create" -> create();
            case "verify" -> verify(required(arg, "backup verify FILE"));
            case "restore" -> {
                if (options.containsKey("--sheet")) {
                    if (arg != null) {
                        throw new UsageException("use 'backup restore FILE' or 'backup restore --sheet', not both");
                    }
                    restoreSheet();
                } else {
                    restore(required(arg, "backup restore FILE (or backup restore --sheet)"));
                }
            }
            case "sheet" -> sheet();
            default -> throw new UsageException("use backup, backup create, backup verify FILE, backup restore FILE,"
                    + " backup restore --sheet or backup sheet");
        }
    }

    /** One line after a command that used the master key, if it has never been backed up. */
    static void remindIfNotBackedUp(PrintStream out, Path configPath, byte[] master) {
        Path folder = configPath.toAbsolutePath().getParent();
        if (!BackupRecord.read(folder, BackupRecord.keyId(master)).any()) {
            out.println();
            out.println("Tip: this master key has no backup yet, and every tag you set up depends on it.");
            out.println("Run 'knurctl backup create' or 'knurctl backup sheet'.");
        }
    }

    private void status() throws IOException, UsageException {
        byte[] master = requireMaster();
        String id = BackupRecord.keyId(master);
        BackupRecord.Status s = BackupRecord.read(folder(), id);
        out.println("Master key ID " + id + " (in " + configPath.toAbsolutePath() + ")");
        out.println("Backup file:     " + s.file().map(BackupCommands::day).orElse("never"));
        out.println("Recovery sheet:  " + s.sheet().map(BackupCommands::day).orElse("never"));
        if (!s.any()) {
            out.println();
            out.println("Not backed up yet. If this computer's disk fails, every tag set up with this key is lost.");
            out.println("Run 'knurctl backup create', 'knurctl backup sheet', or both.");
        }
    }

    private void create() throws IOException, UsageException {
        Properties config = loadConfig();
        byte[] master = Main.masterKeyOf(config, configPath);
        Path folder = folder();
        Map<String, byte[]> files = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String rel = folder.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (SKIP.matcher(file.getFileName().toString()).matches()) {
                    continue;
                }
                if (Files.size(file) > MAX_FILE) {
                    skipped.add(rel);
                    continue;
                }
                files.put(rel, Files.readAllBytes(file));
            }
        }
        Path keyFile = Main.masterKeyFile(config, configPath);
        boolean keyOutside = keyFile != null && !keyFile.startsWith(folder);

        Path target = options.containsKey("--out") ? Path.of(options.get("--out")) : defaultBackupPath(folder);
        if (target.toAbsolutePath().normalize().startsWith(folder)) {
            out.println("Note: " + target + " is inside the folder it backs up. Copy it somewhere else afterwards.");
        }
        char[] passphrase = newPassphrase();
        Instant now = Instant.now();
        byte[] sealed;
        try {
            sealed = BackupFile.seal(files, now, passphrase, new SecureRandom());
        } finally {
            Arrays.fill(passphrase, '\0');
            files.values().forEach(b -> Arrays.fill(b, (byte) 0));
        }
        try {
            writePrivate(target, sealed);
        } catch (FileAlreadyExistsException e) {
            throw new UsageException(target + " already exists; pick another name with --out FILE");
        }

        out.println("Wrote " + target.toAbsolutePath() + " (" + files.size() + " files from " + folder + ").");
        for (String s : skipped) {
            out.println("Left out " + s + ": over 5 MB, so not a setting.");
        }
        if (keyOutside) {
            out.println("Warning: master.key.file is " + keyFile + ", outside this folder, so it is NOT in the backup.");
            out.println("Back it up separately, or also run 'knurctl backup sheet'.");
        }
        if (master != null) {
            BackupRecord.record(folder, BackupRecord.keyId(master), BackupRecord.Kind.FILE, now);
            out.println("Master key ID " + BackupRecord.keyId(master) + ".");
        }
        out.println();
        out.println("Next: copy the file off this computer (a USB stick you keep safe, or your password");
        out.println("manager's file storage) and keep the passphrase in your password manager.");
        out.println("Check a copy any time with: knurctl backup verify FILE");
    }

    private void verify(String file) throws IOException, UsageException {
        BackupFile.Contents contents = open(Path.of(file));
        String settings = configPath.getFileName().toString();
        byte[] saved = masterKeyIn(contents, settings);
        out.println("Backup made " + day(contents.made()) + ", " + contents.files().size() + " files.");
        if (saved == null) {
            throw new IOException("the backup has no master key in " + settings + ": it can't bring your tags back");
        }
        String savedId = BackupRecord.keyId(saved);
        byte[] current = Files.exists(configPath) ? Main.masterKeyOf(loadConfig(), configPath) : null;
        if (current == null) {
            out.println("It holds master key ID " + savedId + ". The passphrase works.");
        } else if (Arrays.equals(current, saved)) {
            out.println("It holds this folder's master key (ID " + savedId + "). The passphrase works.");
        } else {
            throw new IOException("the backup holds master key ID " + savedId + ", but " + configPath
                    + " has ID " + BackupRecord.keyId(current) + ". It's a backup of another setup, or of an older key.");
        }
    }

    private void restore(String file) throws IOException, UsageException {
        Path target = restoreTarget();
        BackupFile.Contents contents = open(Path.of(file));
        String settings = configPath.getFileName().toString();
        if (!contents.files().containsKey(settings)) {
            throw new UsageException("the backup has no " + settings + "; pass --config with the settings file's name");
        }
        for (String rel : contents.files().keySet()) {
            Path dest = target.resolve(rel).normalize();
            if (!dest.startsWith(target)) {
                throw new IOException("the backup holds an unsafe path: " + rel);
            }
            if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                throw new UsageException(dest + " already exists. Restore into an empty folder with --to DIR.");
            }
        }
        for (Map.Entry<String, byte[]> f : contents.files().entrySet()) {
            Path dest = target.resolve(f.getKey()).normalize();
            Files.createDirectories(dest.getParent());
            writePrivate(dest, f.getValue());
        }
        out.println("Restored " + contents.files().size() + " files from the backup made " + day(contents.made())
                + " into " + target + ".");
        out.println("Every file is readable by you only (chmod 600).");
        out.println();
        out.println("Next: start knurd from " + target + " and tap a tag; you should see it verified.");
        out.println("Tap counts come from the backup, so links a tag made after " + day(contents.made())
                + " could work once more. Tap each tag once to move its count past them.");
        out.println("If this is a new computer, see 'Move knurd to another computer' on docs.knura.io.");
    }

    private void restoreSheet() throws IOException, UsageException {
        Path target = restoreTarget();
        Path settings = target.resolve(configPath.getFileName().toString());
        out.println("Type each row as it is on the sheet, check characters included. Spaces don't matter.");
        out.println();
        out.println("master.key");
        byte[] master = readRows("master.key", MASTER_BYTES);
        out.println("Master key ID " + BackupRecord.keyId(master) + ": it should match the ID on the sheet.");
        out.println();

        String owner = ask("tap.knura.io owner ID (press Enter if the sheet has none): ");
        String extra = "";
        if (!owner.isEmpty()) {
            if (!OwnerIds.isValid(owner)) {
                throw new UsageException("'" + owner + "' isn't an owner ID: 10 letters and digits, as on the sheet");
            }
            String url = ask("Resolver address (press Enter for " + ResolverClient.DEFAULT_URL + "): ");
            if (url.isEmpty()) {
                url = ResolverClient.DEFAULT_URL;
            }
            out.println("resolver.key");
            byte[] seed = readRows("resolver.key", RESOLVER_KEY_BYTES);
            OwnerKey key = OwnerKey.fromSeed(Hex.encode(seed));
            Arrays.fill(seed, (byte) 0);
            extra = Main.resolverLines(url, owner, key);
        }
        String content = Main.settingsFile(master) + extra;
        try {
            writePrivate(settings, content.getBytes(StandardCharsets.UTF_8));
        } catch (FileAlreadyExistsException e) {
            throw new UsageException(settings + " already exists. Restore into an empty folder with --to DIR.");
        }
        BackupRecord.record(target, BackupRecord.keyId(master), BackupRecord.Kind.SHEET, Instant.now());
        Arrays.fill(master, (byte) 0);

        out.println();
        out.println("Wrote " + settings + " with your master key" + (owner.isEmpty() ? "." : " and tap.knura.io key."));
        out.println("Next:");
        out.println("  1. Set public.url in it (the address phones reach knurd at).");
        out.println("  2. Start knurd from " + target + " and tap a tag; you should see it verified.");
        if (!owner.isEmpty()) {
            out.println("  3. Run 'knurctl resolver register' so tap.knura.io sends taps to this computer.");
        }
        out.println("The sheet holds keys only: write your action rules again and pair your phones again.");
        out.println("Tap counts start from zero, so tap each tag once before anyone else can reuse an old link.");
    }

    private void sheet() throws IOException, UsageException {
        Properties config = loadConfig();
        byte[] master = requireMaster();
        String id = BackupRecord.keyId(master);
        String owner = config.getProperty("resolver.owner", "").trim();
        OwnerKey resolverKey = null;
        if (!owner.isEmpty()) {
            try {
                resolverKey = OwnerKey.load(config.getProperty("resolver.key", ""), config.getProperty("resolver.publicKey", ""));
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage() + " (in " + configPath + ")");
            }
        }
        String resolverUrl = config.getProperty("resolver.url", ResolverClient.DEFAULT_URL).trim();
        Instant now = Instant.now();
        String html = SheetPage.render(id, day(now), master,
                owner.isEmpty() ? null : owner, resolverUrl, resolverKey == null ? null : Hex.decode(resolverKey.seedHex()));
        Arrays.fill(master, (byte) 0);

        Path target = options.containsKey("--out") ? Path.of(options.get("--out")) : defaultSheetPath(id);
        try {
            writePrivate(target, html.getBytes(StandardCharsets.UTF_8));
        } catch (FileAlreadyExistsException e) {
            throw new UsageException(target + " already exists. Print that one, or delete it and run this again.");
        }
        BackupRecord.record(folder(), id, BackupRecord.Kind.SHEET, now);
        out.println("Wrote the recovery sheet for master key ID " + id + " to:");
        out.println("  " + target.toAbsolutePath());
        if (target.toAbsolutePath().startsWith(Path.of("/dev/shm"))) {
            out.println("It's in memory only (/dev/shm) and is gone when the computer shuts down.");
        }
        out.println();
        out.println("1. Open it in a browser and print it (print to paper, not to PDF).");
        out.println("2. Delete the file:  rm " + target.toAbsolutePath());
        out.println("3. Keep the paper with your passport or other important papers.");
        out.println("To use it: knurctl backup restore --sheet");
    }

    private byte[] readRows(String label, int length) throws IOException, UsageException {
        int rows = RecoverySheet.rowCount(length);
        byte[] secret = new byte[length];
        for (int r = 1; r <= rows; r++) {
            int from = (r - 1) * RecoverySheet.ROW_BYTES;
            for (int attempt = 1; ; attempt++) {
                String typed = ask("  row " + r + ": ");
                try {
                    byte[] part = RecoverySheet.readRow(label, r, typed);
                    int expected = Math.min(RecoverySheet.ROW_BYTES, length - from);
                    if (part.length != expected) {
                        throw new UsageException("row " + r + " should have " + expected * 2 + " characters before its check");
                    }
                    System.arraycopy(part, 0, secret, from, part.length);
                    break;
                } catch (SheetException | UsageException e) {
                    if (attempt == TRIES) {
                        throw new UsageException(e.getMessage());
                    }
                    out.println("  " + e.getMessage() + ". Try that row again.");
                }
            }
        }
        return secret;
    }

    private String ask(String prompt) throws IOException, UsageException {
        String line = prompter.line(prompt);
        if (line == null) {
            throw new UsageException("stopped: no more input");
        }
        return line.trim();
    }

    private char[] newPassphrase() throws IOException, UsageException {
        out.println("Choose a passphrase for the backup and keep it in your password manager.");
        out.println("Without it, the backup can't be opened by anyone, you included.");
        char[] first = prompter.secret("Passphrase: ");
        if (first == null) {
            throw new UsageException("stopped: no passphrase given");
        }
        if (first.length < BackupFile.MIN_PASSPHRASE) {
            Arrays.fill(first, '\0');
            throw new UsageException("use a passphrase of at least " + BackupFile.MIN_PASSPHRASE
                    + " characters; a few random words work well");
        }
        char[] again = prompter.secret("Same passphrase again: ");
        boolean same = again != null && Arrays.equals(first, again);
        if (again != null) {
            Arrays.fill(again, '\0');
        }
        if (!same) {
            Arrays.fill(first, '\0');
            throw new UsageException("the passphrases didn't match; nothing was written");
        }
        return first;
    }

    private BackupFile.Contents open(Path file) throws IOException, UsageException {
        if (!Files.isRegularFile(file)) {
            throw new UsageException("no backup file " + file);
        }
        byte[] data = Files.readAllBytes(file);
        char[] passphrase = prompter.secret("Backup passphrase: ");
        if (passphrase == null) {
            throw new UsageException("stopped: no passphrase given");
        }
        try {
            return BackupFile.open(data, passphrase);
        } catch (BackupException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    /** The master key a backup holds: master.key in its settings file, or the master.key.file it names. */
    private static byte[] masterKeyIn(BackupFile.Contents contents, String settings) throws UsageException {
        byte[] file = contents.files().get(settings);
        if (file == null) {
            return null;
        }
        Properties p = new Properties();
        try (Reader in = new InputStreamReader(new ByteArrayInputStream(file), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String hex = p.getProperty("master.key", "").trim();
        String keyFile = p.getProperty("master.key.file", "").trim().replace('\\', '/');
        if (hex.isEmpty() && !keyFile.isEmpty() && contents.files().containsKey(keyFile)) {
            hex = new String(contents.files().get(keyFile), StandardCharsets.UTF_8).trim();
        }
        try {
            byte[] key = Hex.decode(hex);
            return key.length == MASTER_BYTES ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Path restoreTarget() throws IOException, UsageException {
        Path target = (options.containsKey("--to") ? Path.of(options.get("--to")) : folder()).toAbsolutePath().normalize();
        Files.createDirectories(target);
        Path settings = target.resolve(configPath.getFileName().toString());
        if (Files.exists(settings)) {
            throw new UsageException(settings + " already exists, and restoring never overwrites a master key."
                    + " Restore into an empty folder with --to DIR.");
        }
        return target;
    }

    private byte[] requireMaster() throws IOException, UsageException {
        if (!Files.exists(configPath)) {
            throw new UsageException(configPath.toAbsolutePath() + " not found; create it with: knurctl init");
        }
        byte[] master = Main.masterKeyOf(loadConfig(), configPath);
        if (master == null) {
            throw new UsageException("no master.key in " + configPath.toAbsolutePath()
                    + "; backups are for master-key setups (knurctl init)");
        }
        return master;
    }

    private Properties loadConfig() throws IOException, UsageException {
        if (!Files.exists(configPath)) {
            throw new UsageException(configPath.toAbsolutePath() + " not found; run this in your settings folder or pass --config");
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    private Path folder() {
        return configPath.toAbsolutePath().normalize().getParent();
    }

    private static String required(String arg, String usage) throws UsageException {
        if (arg == null) {
            throw new UsageException("usage: knurctl " + usage);
        }
        return arg;
    }

    private static Path defaultBackupPath(Path folder) {
        Path dir = Optional.ofNullable(folder.getParent()).orElse(folder);
        String base = "knura-backup-" + LocalDate.now().format(DAY);
        Path path = dir.resolve(base + BackupFile.SUFFIX);
        for (int n = 2; Files.exists(path); n++) {
            path = dir.resolve(base + "-" + n + BackupFile.SUFFIX);
        }
        return path;
    }

    private Path defaultSheetPath(String id) {
        Path shm = Path.of("/dev/shm");
        Path dir = Files.isDirectory(shm) && Files.isWritable(shm) ? shm : Optional.ofNullable(folder().getParent()).orElse(folder());
        return dir.resolve("knura-recovery-sheet-" + id + ".html");
    }

    private static String day(Instant when) {
        return when.atZone(ZoneId.systemDefault()).toLocalDate().format(DAY);
    }

    /** Writes a new file only its owner can read; never replaces one. */
    private static void writePrivate(Path file, byte[] content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        boolean posix = dir != null && Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null;
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Files.createFile(file, attrs);
        Files.write(file, content);
    }
}
