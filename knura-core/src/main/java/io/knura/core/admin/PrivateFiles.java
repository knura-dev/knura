package io.knura.core.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/** Files holding secrets, which only their owner may read or change (chmod 600). */
public final class PrivateFiles {
    private static final Set<PosixFilePermission> NOT_OWNER = EnumSet.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private PrivateFiles() {}

    /**
     * Returns a problem description, with the chmod that fixes it, if other users can read or
     * change {@code file}; null if only its owner can (or the file system has no POSIX
     * permissions to check).
     */
    public static String problem(Path file) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view == null) {
            return null;
        }
        Set<PosixFilePermission> perms = EnumSet.copyOf(view.readAttributes().permissions());
        perms.retainAll(NOT_OWNER);
        return perms.isEmpty() ? null : file + " can be read or changed by other users; run: chmod 600 " + file;
    }

    /** Writes a new file that only its owner can read; throws {@link java.nio.file.FileAlreadyExistsException} if {@code file} exists. */
    public static void create(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Files.createFile(file, ownerOnly(file));
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Replaces {@code file} in one step with an owner-only file holding {@code content}. */
    public static void replace(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp", ownerOnly(file));
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static FileAttribute<?>[] ownerOnly(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        boolean posix = dir != null && Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null;
        return posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
    }
}
