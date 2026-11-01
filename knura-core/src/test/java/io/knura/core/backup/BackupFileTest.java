package io.knura.core.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.backup.BackupFile.BackupException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackupFileTest {
    private static final Instant MADE = Instant.parse("2026-11-01T15:00:00Z");
    /** Fast enough for tests; real backups use {@link BackupFile#ITERATIONS}. */
    private static final int FAST = 1000;

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    BackupFileTest() {
        files.put("knurd.properties", "master.key=00112233445566778899AABBCCDDEEFF\n".getBytes(StandardCharsets.UTF_8));
        files.put("keys/admin.p12", new byte[] {1, 2, 3});
    }

    private byte[] seal(String passphrase) {
        return BackupFile.seal(files, MADE, passphrase.toCharArray(), new SecureRandom(), FAST);
    }

    @Test
    void roundTrips() throws Exception {
        BackupFile.Contents back = BackupFile.open(seal("correct horse battery"), "correct horse battery".toCharArray());
        assertEquals(MADE, back.made());
        assertEquals(files.keySet(), back.files().keySet());
        assertArrayEquals(files.get("keys/admin.p12"), back.files().get("keys/admin.p12"));
    }

    @Test
    void holdsNoPlainText() {
        String sealed = new String(seal("correct horse battery"), StandardCharsets.ISO_8859_1);
        assertTrue(sealed.startsWith("KNURABK1"));
        assertFalse(sealed.contains("master.key"));
        assertFalse(sealed.contains("knurd.properties"), "even file names are encrypted");
    }

    @Test
    void wrongPassphraseFails() {
        BackupException e = assertThrows(BackupException.class,
                () -> BackupFile.open(seal("correct horse battery"), "correct horse battery!".toCharArray()));
        assertTrue(e.getMessage().contains("wrong passphrase"));
    }

    @Test
    void anyChangedByteFails() {
        byte[] sealed = seal("correct horse battery");
        for (int at : new int[] {9, 14, 30, 40, sealed.length - 1}) {
            byte[] bad = sealed.clone();
            bad[at] ^= 1;
            assertThrows(BackupException.class, () -> BackupFile.open(bad, "correct horse battery".toCharArray()), "byte " + at);
        }
    }

    @Test
    void refusesOtherFiles() {
        BackupException e = assertThrows(BackupException.class,
                () -> BackupFile.open("hello".getBytes(StandardCharsets.UTF_8), "x".toCharArray()));
        assertEquals("not a Knura backup file", e.getMessage());
    }

    @Test
    void eachBackupIsDifferent() {
        assertFalse(java.util.Arrays.equals(seal("same passphrase"), seal("same passphrase")), "fresh salt and nonce");
    }

    @Test
    void safePaths() {
        assertTrue(BackupFile.safePath("knurd.properties"));
        assertTrue(BackupFile.safePath("keys/admin.p12"));
        for (String bad : new String[] {"", "/etc/passwd", "../x", "a/../../x", "a//b", "C:/x", "a\\b", "./x"}) {
            assertFalse(BackupFile.safePath(bad), bad);
        }
        Map<String, byte[]> evil = Map.of("../outside", new byte[1]);
        assertThrows(IllegalArgumentException.class,
                () -> BackupFile.seal(evil, MADE, "passphrase".toCharArray(), new SecureRandom(), FAST));
    }
}
