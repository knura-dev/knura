package io.knura.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFilesTest {
    @TempDir
    Path dir;

    @Test
    void createsOwnerOnlyFilesAndNeverOverwrites() throws Exception {
        Path file = dir.resolve("secret");
        PrivateFiles.create(file, "one");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertNull(PrivateFiles.problem(file));
        assertThrows(FileAlreadyExistsException.class, () -> PrivateFiles.create(file, "two"));
        assertEquals("one", Files.readString(file));
    }

    @Test
    void replaceKeepsItPrivate() throws Exception {
        Path file = dir.resolve("secret");
        Files.writeString(file, "old");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertTrue(PrivateFiles.problem(file).contains("chmod 600"));
        PrivateFiles.replace(file, "new");
        assertEquals("new", Files.readString(file));
        assertNull(PrivateFiles.problem(file));
    }
}
