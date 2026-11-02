package io.knura.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding the code checkout a settings folder or backup sits in, so knurd and knurctl can warn. */
class SettingsFolderTest {
    @TempDir
    Path dir;

    @Test
    void findsTheNearestCodeCheckout() throws Exception {
        Path clone = Files.createDirectories(dir.resolve("knura"));
        Files.createDirectory(clone.resolve(".git"));
        Path settings = Files.createDirectories(clone.resolve("knura"));
        assertEquals(clone, SettingsFolder.codeClone(settings));
        assertEquals(clone, SettingsFolder.codeClone(clone));
        assertEquals(clone, SettingsFolder.codeClone(settings.resolve("../knura")), "paths are tidied first");
    }

    @Test
    void aFolderOutsideAnyCheckoutHasNone() throws Exception {
        Path home = Files.createDirectories(dir.resolve("knura-home"));
        Files.createDirectories(dir.resolve("knura/.git"));
        assertNull(SettingsFolder.codeClone(home));
    }
}
