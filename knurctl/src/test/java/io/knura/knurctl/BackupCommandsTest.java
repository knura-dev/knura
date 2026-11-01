package io.knura.knurctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.backup.BackupRecord;
import io.knura.core.backup.RecoverySheet;
import io.knura.core.crypto.Hex;
import io.knura.core.resolver.OwnerKey;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupCommandsTest {
    private static final String MASTER = "3F9A21C07B44E2D10A6E91F322B84C07";
    private static final String PASS = "correct horse battery";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Deque<String> answers = new ArrayDeque<>();

    private final Prompter prompter = new Prompter() {
        @Override
        public String line(String prompt) {
            return answers.poll();
        }

        @Override
        public char[] secret(String prompt) {
            String a = answers.poll();
            return a == null ? null : a.toCharArray();
        }
    };

    private int run(String... args) {
        out.reset();
        err.reset();
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), prompter);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path home() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve("knurd.properties"), "listen.port=8424\nmaster.key=" + MASTER + "\n");
        Files.writeString(home.resolve("knurgn.properties"), "knot.door.uids=04DE5F1EACC040\n");
        Files.writeString(home.resolve("knurd.log"), "lots of log lines\n");
        return home;
    }

    private String config(Path home) {
        return home.resolve("knurd.properties").toString();
    }

    @Test
    void createVerifyAndRestore() throws Exception {
        Path home = home();
        Path backup = dir.resolve("b.knurabackup");
        answers.addAll(List.of(PASS, PASS));
        assertEquals(0, run("backup", "create", "--config", config(home), "--out", backup.toString()), err());
        assertTrue(out().contains("2 files"), out());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(backup)));
        assertTrue(BackupRecord.read(home, BackupRecord.keyId(Hex.decode(MASTER))).file().isPresent());

        answers.add(PASS);
        assertEquals(0, run("backup", "verify", backup.toString(), "--config", config(home)), err());
        assertTrue(out().contains("this folder's master key"), out());

        answers.add("not the passphrase");
        assertEquals(1, run("backup", "verify", backup.toString(), "--config", config(home)));
        assertTrue(err().contains("wrong passphrase"), err());

        Path fresh = dir.resolve("fresh");
        answers.add(PASS);
        assertEquals(0, run("backup", "restore", backup.toString(), "--config", fresh.resolve("knurd.properties").toString()), err());
        assertEquals(Files.readString(home.resolve("knurd.properties")), Files.readString(fresh.resolve("knurd.properties")));
        assertTrue(Files.exists(fresh.resolve("knurgn.properties")));
        assertFalse(Files.exists(fresh.resolve("knurd.log")), "logs aren't backed up");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fresh.resolve("knurgn.properties"))));

        answers.add(PASS);
        assertEquals(2, run("backup", "restore", backup.toString(), "--to", fresh.toString()));
        assertTrue(err().contains("never overwrites a master key"), err());
    }

    @Test
    void verifySpotsABackupOfAnotherKey() throws Exception {
        Path home = home();
        Path backup = dir.resolve("b.knurabackup");
        answers.addAll(List.of(PASS, PASS));
        assertEquals(0, run("backup", "create", "--config", config(home), "--out", backup.toString()));
        Files.writeString(home.resolve("knurd.properties"), "master.key=00112233445566778899AABBCCDDEEFF\n");
        answers.add(PASS);
        assertEquals(1, run("backup", "verify", backup.toString(), "--config", config(home)));
        assertTrue(err().contains("another setup"), err());
    }

    @Test
    void createRefusesShortOrMismatchedPassphrases() throws Exception {
        Path home = home();
        answers.add("short");
        assertEquals(2, run("backup", "create", "--config", config(home), "--out", dir.resolve("a").toString()));
        answers.addAll(List.of(PASS, PASS + "x"));
        assertEquals(2, run("backup", "create", "--config", config(home), "--out", dir.resolve("a").toString()));
        assertTrue(err().contains("didn't match"), err());
        assertFalse(Files.exists(dir.resolve("a")));
    }

    @Test
    void statusAndReminder() throws Exception {
        Path home = home();
        assertEquals(0, run("backup", "--config", config(home)));
        assertTrue(out().contains("Not backed up yet"), out());

        ByteArrayOutputStream tip = new ByteArrayOutputStream();
        BackupCommands.remindIfNotBackedUp(new PrintStream(tip, true, StandardCharsets.UTF_8), home.resolve("knurd.properties"), Hex.decode(MASTER));
        assertTrue(tip.toString(StandardCharsets.UTF_8).contains("no backup yet"));

        assertEquals(0, run("backup", "sheet", "--config", config(home), "--out", dir.resolve("s.html").toString()));
        assertEquals(0, run("backup", "status", "--config", config(home)));
        assertFalse(out().contains("Not backed up yet"), out());
        tip.reset();
        BackupCommands.remindIfNotBackedUp(new PrintStream(tip, true, StandardCharsets.UTF_8), home.resolve("knurd.properties"), Hex.decode(MASTER));
        assertEquals("", tip.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sheetRoundTripsThroughRestore() throws Exception {
        Path home = home();
        OwnerKey owner = OwnerKey.generate();
        Files.writeString(home.resolve("knurd.properties"), Main.resolverLines("https://tap.knura.io", "abcdefghjk", owner),
                java.nio.file.StandardOpenOption.APPEND);
        Path sheet = dir.resolve("sheet.html");
        assertEquals(0, run("backup", "sheet", "--config", config(home), "--out", sheet.toString()), err());
        String html = Files.readString(sheet);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(sheet)));
        assertTrue(html.contains(BackupRecord.keyId(Hex.decode(MASTER))));
        assertTrue(html.contains("abcdefghjk"));
        assertFalse(html.contains("<script"), "no scripts on the sheet");
        assertFalse(html.contains(MASTER), "keys only appear as checked rows");

        // Type it back in, with one typo on the first try of master row 2.
        List<String> masterRows = RecoverySheet.rows("master.key", Hex.decode(MASTER));
        List<String> resolverRows = RecoverySheet.rows("resolver.key", Hex.decode(owner.seedHex()));
        for (String row : masterRows) {
            assertTrue(html.contains(row.trim().substring(0, 19)), row);
        }
        answers.add(masterRows.get(0));
        answers.add(masterRows.get(1).replace('E', 'F'));
        answers.add(masterRows.get(1));
        answers.add("abcdefghjk");
        answers.add("");
        answers.addAll(resolverRows);
        Path fresh = dir.resolve("fresh");
        assertEquals(0, run("backup", "restore", "--sheet", "--to", fresh.toString()), err());
        assertTrue(out().contains("Try that row again"), out());

        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(fresh.resolve("knurd.properties"))) {
            p.load(in);
        }
        assertEquals(MASTER, p.getProperty("master.key").toUpperCase());
        assertEquals("abcdefghjk", p.getProperty("resolver.owner"));
        assertEquals(owner.publicKeyHex(), p.getProperty("resolver.publicKey"));
        assertEquals(owner.seedHex(), p.getProperty("resolver.key"));
        assertEquals("https://tap.knura.io", p.getProperty("resolver.url"));
    }

    @Test
    void sheetRestoreWithoutResolver() throws Exception {
        List<String> rows = new ArrayList<>(RecoverySheet.rows("master.key", Hex.decode(MASTER)));
        answers.addAll(rows);
        answers.add("");
        Path fresh = dir.resolve("fresh");
        assertEquals(0, run("backup", "restore", "--sheet", "--to", fresh.toString()), err());
        String written = Files.readString(fresh.resolve("knurd.properties"));
        assertTrue(written.toUpperCase().contains("MASTER.KEY=" + MASTER), written);
        assertFalse(written.contains("resolver.owner="));
    }

    @Test
    void readsMasterKeyFile() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve("knurd.properties"), "master.key.file=master.key\n");
        Files.writeString(home.resolve("master.key"), MASTER + "\n");
        answers.addAll(List.of(PASS, PASS));
        Path backup = dir.resolve("b.knurabackup");
        assertEquals(0, run("backup", "create", "--config", config(home), "--out", backup.toString()), err());
        answers.add(PASS);
        assertEquals(0, run("backup", "verify", backup.toString(), "--config", config(home)), err());
        assertTrue(out().contains("this folder's master key"), out());
    }
}
