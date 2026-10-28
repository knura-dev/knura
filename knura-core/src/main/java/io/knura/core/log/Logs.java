package io.knura.core.log;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Sets up logging for Knura's programs (knurd, knura-link), on the JDK's own logging so there
 * are no third-party dependencies. Code logs through {@link System.Logger}; this decides how
 * much is written and where.
 *
 * <p>Two optional settings, in the program's own config file:
 *
 * <pre>
 * log.level=info          # error, warn, info (default), debug or trace
 * log.file=logs/knurd.log # also write to this file; at 10 MB it moves to .1 (4 old files kept)
 * </pre>
 *
 * The environment variable {@code KNURA_LOG_LEVEL} overrides {@code log.level}, handy for one
 * troubleshooting run. Messages always go to standard error too, where the systemd journal,
 * Docker and Kubernetes pick them up. Every line passes through {@link Redact}.
 */
public final class Logs {
    public static final String LEVEL_SETTING = "log.level";
    public static final String FILE_SETTING = "log.file";
    public static final String LEVEL_ENV = "KNURA_LOG_LEVEL";

    static final int FILE_LIMIT_BYTES = 10 * 1024 * 1024;
    /** Older files kept besides the current one: knurd.log.1 to knurd.log.4. */
    static final int FILE_KEEP = 4;

    private Logs() {}

    /** The logging settings: a level, and a file or null for standard error only. */
    public record Settings(Level level, Path file) {}

    /**
     * Reads {@code log.level} and {@code log.file} from {@code configFile} (relative paths from its
     * folder) and the environment.
     *
     * @throws IllegalArgumentException if the level isn't one Knura knows
     */
    public static Settings read(Path configFile, Map<String, String> env) throws IOException {
        Properties p = new Properties();
        if (configFile != null && Files.exists(configFile)) {
            try (Reader in = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                p.load(in);
            }
        }
        String level = env.getOrDefault(LEVEL_ENV, "").trim();
        String where = LEVEL_ENV;
        if (level.isEmpty()) {
            level = p.getProperty(LEVEL_SETTING, "info").trim();
            where = LEVEL_SETTING;
        }
        Level parsed = parseLevel(level);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    where + " must be error, warn, info, debug or trace, not '" + level + "'");
        }
        String file = p.getProperty(FILE_SETTING, "").trim();
        Path logFile = null;
        if (!file.isEmpty()) {
            Path base = configFile == null ? Path.of("") : configFile.toAbsolutePath().getParent();
            logFile = base.resolve(file);
        }
        return new Settings(parsed, logFile);
    }

    /** Applies {@code settings} to every logger in this program. */
    public static void apply(Settings settings) throws IOException {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(settings.level());
        // The journal stamps each line itself; systemd sets JOURNAL_STREAM for services it starts.
        boolean journal = System.getenv("JOURNAL_STREAM") != null;
        Handler console = new ConsoleHandler();
        console.setLevel(Level.ALL);
        console.setFormatter(new LineFormatter(!journal));
        root.addHandler(console);
        if (settings.file() != null) {
            Path parent = settings.file().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Handler file = new RollingFileHandler(settings.file(), FILE_LIMIT_BYTES, FILE_KEEP, new LineFormatter(true));
            file.setLevel(Level.ALL);
            root.addHandler(file);
        }
        // The JDK itself logs noisily at debug (HTTP internals, a stack trace on every exit); keep it at info.
        if (settings.level().intValue() < Level.INFO.intValue()) {
            for (String noisy : new String[] {"jdk", "sun", "java", "javax", "com.sun"}) {
                Logger.getLogger(noisy).setLevel(Level.INFO);
            }
        }
    }

    /**
     * {@link #read} and {@link #apply} for a program's {@code main}: prints the problem and
     * returns false if the settings are wrong or the log file can't be opened.
     */
    public static boolean setUp(String program, Path configFile) {
        try {
            apply(read(configFile, System.getenv()));
            return true;
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(program + ": bad logging setting: " + e.getMessage());
            return false;
        }
    }

    static Level parseLevel(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "error" -> Level.SEVERE;
            case "warn", "warning" -> Level.WARNING;
            case "info" -> Level.INFO;
            case "debug" -> Level.FINE;
            case "trace" -> Level.FINER;
            case "off" -> Level.OFF;
            default -> null;
        };
    }
}
