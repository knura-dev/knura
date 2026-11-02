package io.knura.core.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What goes into a backup of a settings folder: every regular file in it, by its path relative to
 * the folder with {@code /} separators, except logs, temporary files, other backups and anything
 * over 5 MB (not a setting). Symbolic links are not followed.
 */
public final class SettingsFolder {
    static final Pattern SKIP = Pattern.compile(".*(\\.log(\\.\\d+)?|\\.tmp|" + Pattern.quote(BackupFile.SUFFIX) + ")");
    public static final long MAX_FILE = 5L * 1024 * 1024;

    /** The files to back up, and the paths left out for being too large. */
    public record Collected(Map<String, byte[]> files, List<String> skipped) {}

    private SettingsFolder() {}

    /**
     * The code checkout (a folder with {@code .git}) that {@code path} sits in, or null. Settings
     * or a backup kept inside one are lost when the checkout is deleted to clone it again, so knurd
     * and knurctl warn about it.
     */
    public static Path codeClone(Path path) {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return p;
            }
        }
        return null;
    }

    public static Collected collect(Path folder) throws IOException {
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
        return new Collected(files, skipped);
    }
}
