package io.knura.knurd;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The "Knura" app the Windows and Mac installers put in the Start menu or Applications folder. It
 * makes sure knurd runs in the background, starting each time you sign in, then opens the setup
 * page in your browser. Opening it again just opens the page.
 *
 * <ul>
 *   <li>Windows: settings in {@code %LOCALAPPDATA%\KnuraData}; starts at sign-in through the
 *       {@code HKCU\...\Run} registry key.
 *   <li>Mac: settings in {@code ~/Library/Application Support/Knura}; starts at sign-in through a
 *       LaunchAgent, {@code ~/Library/LaunchAgents/io.knura.knurd.plist}, which also restarts it.
 * </ul>
 *
 * <p>{@code Knura --no-start-at-sign-in} undoes the sign-in start, as before uninstalling.
 */
public final class Desktop {
    static final String SETUP = "http://127.0.0.1:8425/setup";
    static final String LABEL = "io.knura.knurd";
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private Desktop() {}

    public static void main(String[] args) throws Exception {
        boolean windows = os().startsWith("windows");
        boolean mac = os().startsWith("mac");
        if (args.length == 1 && args[0].equals("--no-start-at-sign-in")) {
            if (windows) {
                run(List.of("reg", "delete", RUN_KEY, "/v", "Knura", "/f"));
            } else if (mac) {
                Path plist = launchAgent();
                run(List.of("launchctl", "bootout", "gui/" + uid(), plist.toString()));
                Files.deleteIfExists(plist);
            }
            return;
        }
        if (!answers()) {
            Path knurd = knurdLauncher(windows);
            Path home = home(windows, mac);
            if (windows) {
                run(List.of("reg", "add", RUN_KEY, "/v", "Knura", "/t", "REG_SZ", "/d",
                        "\"" + knurd + "\" --home \"" + home + "\"", "/f"));
                new ProcessBuilder(knurd.toString(), "--home", home.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
            } else if (mac) {
                Path plist = launchAgent();
                Files.createDirectories(plist.getParent());
                Files.writeString(plist, plist(knurd, home), StandardCharsets.UTF_8);
                run(List.of("launchctl", "bootout", "gui/" + uid(), plist.toString())); // an older copy, if loaded
                run(List.of("launchctl", "bootstrap", "gui/" + uid(), plist.toString()));
            } else {
                new ProcessBuilder(knurd.toString(), "--home", home.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
            }
            for (int i = 0; i < 60 && !answers(); i++) {
                Thread.sleep(500);
            }
        }
        open(windows, mac);
    }

    /** The LaunchAgent that starts knurd at sign-in and keeps it running. */
    static String plist(Path knurd, Path home) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>Label</key><string>%s</string>
                  <key>ProgramArguments</key>
                  <array><string>%s</string><string>--home</string><string>%s</string></array>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
                  <key>ProcessType</key><string>Background</string>
                </dict>
                </plist>
                """.formatted(LABEL, xml(knurd.toString()), xml(home.toString()));
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Where the settings live for this user. */
    static Path home(boolean windows, boolean mac) {
        String user = System.getProperty("user.home");
        if (windows) {
            String local = System.getenv("LOCALAPPDATA");
            // Not %LOCALAPPDATA%\Knura: the per-user installer puts the app itself there.
            return Path.of(local != null && !local.isBlank() ? local : user + "\\AppData\\Local", "KnuraData");
        }
        if (mac) {
            return Path.of(user, "Library", "Application Support", "Knura");
        }
        return Path.of(user, ".local", "share", "knura");
    }

    private static Path launchAgent() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", LABEL + ".plist");
    }

    /** The knurd launcher next to this app's own launcher. */
    private static Path knurdLauncher(boolean windows) {
        Optional<String> self = ProcessHandle.current().info().command();
        if (self.isEmpty()) {
            throw new IllegalStateException("can't tell where Knura is installed");
        }
        return Path.of(self.get()).resolveSibling(windows ? "knurd.exe" : "knurd");
    }

    private static String uid() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor();
        return out;
    }

    /** Whether the setup page answers already. */
    static boolean answers() {
        try {
            HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(SETUP)).timeout(Duration.ofSeconds(3))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void open(boolean windows, boolean mac) throws IOException, InterruptedException {
        if (windows) {
            run(List.of("rundll32", "url.dll,FileProtocolHandler", SETUP));
        } else if (mac) {
            run(List.of("open", SETUP));
        } else {
            run(List.of("xdg-open", SETUP));
        }
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
                .waitFor();
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }
}
