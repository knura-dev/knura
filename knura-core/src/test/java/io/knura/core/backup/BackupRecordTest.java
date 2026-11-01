package io.knura.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupRecordTest {
    @TempDir
    Path dir;

    private final String key = BackupRecord.keyId(new byte[16]);
    private final Instant t1 = Instant.parse("2026-11-01T10:00:00Z");
    private final Instant t2 = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void keyIdIsShortAndSaysNothingAboutTheKey() {
        assertTrue(key.matches("[0-9A-F]{8}"), key);
        assertNotEquals(key, BackupRecord.keyId(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertFalse(key.startsWith("00000000"));
    }

    @Test
    void nothingRecordedAtFirst() {
        assertFalse(BackupRecord.read(dir, key).any());
    }

    @Test
    void remembersBothKinds() throws Exception {
        BackupRecord.record(dir, key, BackupRecord.Kind.FILE, t1);
        BackupRecord.record(dir, key, BackupRecord.Kind.SHEET, t2);
        BackupRecord.Status s = BackupRecord.read(dir, key);
        assertEquals(Optional.of(t1), s.file());
        assertEquals(Optional.of(t2), s.sheet());
        assertEquals(Optional.of(t2), s.latest());
        assertFalse(Files.readString(dir.resolve(BackupRecord.FILE_NAME)).toLowerCase().contains("master.key="));
    }

    @Test
    void aNewKeyStartsOver() throws Exception {
        BackupRecord.record(dir, key, BackupRecord.Kind.FILE, t1);
        String other = BackupRecord.keyId(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        assertFalse(BackupRecord.read(dir, other).any());
        BackupRecord.record(dir, other, BackupRecord.Kind.SHEET, t2);
        assertEquals(Optional.empty(), BackupRecord.read(dir, other).file(), "the old key's file backup doesn't count");
        assertFalse(BackupRecord.read(dir, key).any());
    }

    @Test
    void aDamagedRecordCountsAsNone() throws Exception {
        Files.writeString(dir.resolve(BackupRecord.FILE_NAME), "key.id=" + key + "\nfile.made=yesterday\n");
        assertFalse(BackupRecord.read(dir, key).any());
    }
}
