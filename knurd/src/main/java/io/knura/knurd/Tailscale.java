package io.knura.knurd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The setup page's Tailscale helper: asks the {@code tailscale} command whether it is installed and
 * signed in, and turns on Tailscale Funnel for knurd's tap port, so phones on the internet can reach
 * knurd at {@code https://<this computer>.<tailnet>.ts.net}.
 *
 * <p>It only ever runs {@code tailscale status --json}, {@code tailscale funnel status --json} and
 * {@code tailscale funnel --bg <tap port>}: fixed arguments, nothing from the request. Funnel shares
 * only the tap port, never the admin port.
 */
final class Tailscale {
    /** What the page shows. {@code state} is Tailscale's BackendState, or "NotInstalled". */
    record Status(String state, String loginUrl, String dnsName, boolean httpsCerts, boolean funnel, String detail) {
        /** The address phones use once Funnel is on: https and the computer's tailnet name. */
        String url() {
            return dnsName == null ? null : "https://" + dnsName;
        }
    }

    /** The outcome of turning Funnel on: {@code fixUrl} or {@code fixCommand} say what the person must do first. */
    record Funnel(boolean on, String fixUrl, String fixCommand, String detail) {}

    private static final Pattern LOGIN_URL = Pattern.compile("https://login\\.tailscale\\.com/[A-Za-z0-9/_?=&.%-]+");
    private static final long STATUS_WAIT = 8;

    private final List<String> command;
    private final int tapPort;
    private final long funnelWait;

    /** @param command how to run the Tailscale command line, or null when it isn't installed */
    Tailscale(List<String> command, int tapPort) {
        this(command, tapPort, 20);
    }

    /** @param funnelWait seconds to wait for {@code tailscale funnel}, which may wait for someone to approve it */
    Tailscale(List<String> command, int tapPort, long funnelWait) {
        this.command = command;
        this.tapPort = tapPort;
        this.funnelWait = funnelWait;
    }

    /** Finds the {@code tailscale} command: on the PATH, or where the Mac and Windows apps put it. */
    static Tailscale find(int tapPort) {
        List<Path> places = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    places.add(Path.of(dir, "tailscale"));
                    places.add(Path.of(dir, "tailscale.exe"));
                }
            }
        }
        places.add(Path.of("/Applications/Tailscale.app/Contents/MacOS/Tailscale"));
        places.add(Path.of("C:\\Program Files\\Tailscale\\tailscale.exe"));
        for (Path p : places) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return new Tailscale(List.of(p.toString()), tapPort);
            }
        }
        return new Tailscale(null, tapPort);
    }

    Status status() {
        if (command == null) {
            return new Status("NotInstalled", null, null, false, false, "the tailscale command was not found");
        }
        Result r = run(STATUS_WAIT, "status", "--json");
        if (r == null || r.out.isBlank() || !r.out.trim().startsWith("{")) {
            String why = r == null ? "tailscale did not answer" : firstLine(r.out);
            return new Status("NotRunning", null, null, false, false, why);
        }
        String json = r.out;
        String state = field(json, "BackendState");
        String loginUrl = field(json, "AuthURL");
        int self = json.indexOf("\"Self\"");
        String dns = self < 0 ? null : field(json.substring(self), "DNSName");
        if (dns != null && dns.endsWith(".")) {
            dns = dns.substring(0, dns.length() - 1);
        }
        if (dns != null && !dns.matches("[A-Za-z0-9.-]{1,253}")) {
            dns = null;
        }
        boolean certs = Pattern.compile("\"CertDomains\"\\s*:\\s*\\[\\s*\"").matcher(json).find();
        boolean funnel = "Running".equals(state) && funnelOn(dns);
        return new Status(state == null ? "Unknown" : state, loginUrl == null || loginUrl.isEmpty() ? null : loginUrl,
                dns, certs, funnel, null);
    }

    /** Whether Funnel is on for this computer's name and passes to the tap port. */
    private boolean funnelOn(String dns) {
        if (dns == null) {
            return false;
        }
        Result r = run(STATUS_WAIT, "funnel", "status", "--json");
        if (r == null || r.exit != 0) {
            return false;
        }
        String json = r.out.replaceAll("\\s", "");
        String host = Pattern.quote(dns.toLowerCase(Locale.ROOT));
        boolean allowed = Pattern.compile("\"AllowFunnel\":\\{[^}]*\"" + host + ":443\":true", Pattern.CASE_INSENSITIVE)
                .matcher(json).find();
        boolean toTapPort = Pattern.compile("\"Proxy\":\"http://(127\\.0\\.0\\.1|localhost):" + tapPort + "/?\"")
                .matcher(json).find();
        return allowed && toTapPort;
    }

    /** Turns Funnel on for the tap port, in the background so it stays on after this returns. */
    Funnel turnOnFunnel() {
        if (command == null) {
            return new Funnel(false, "https://tailscale.com/download", null, "Tailscale isn't installed");
        }
        Result r = run(funnelWait, "funnel", "--bg", String.valueOf(tapPort));
        String out = r == null ? "" : r.out;
        String lower = out.toLowerCase(Locale.ROOT);
        Matcher login = LOGIN_URL.matcher(out);
        if (r != null && r.exit == 0 && !login.find()) {
            return new Funnel(true, null, null, null);
        }
        login.reset();
        if (login.find()) {
            // Funnel or HTTPS certificates aren't allowed on this tailnet yet: the link turns them on.
            return new Funnel(false, login.group(), null, "Tailscale needs you to allow Funnel for your network");
        }
        if (lower.contains("access denied") || lower.contains("permission denied") || lower.contains("operator")) {
            return new Funnel(false, null, "sudo tailscale set --operator=" + operator(),
                    "Tailscale needs permission for Knura's user to share a port");
        }
        return new Funnel(false, null, null, r == null || r.exit == -1 && out.isBlank() ? "tailscale did not answer in time" : firstLine(out));
    }

    /** The user knurd runs as, for {@code tailscale set --operator}: "knura" for the Linux service. */
    static String operator() {
        String user = System.getProperty("user.name", "");
        return user.matches("[a-z_][a-z0-9_.-]{0,31}") ? user : "$USER";
    }

    private record Result(int exit, String out) {}

    private Result run(long seconds, String... args) {
        List<String> cmd = new ArrayList<>(command);
        cmd.addAll(List.of(args));
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                    .start();
        } catch (IOException e) {
            return null;
        }
        // Read in the background: "tailscale funnel" can print a link and then wait for someone to open it.
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = p.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    synchronized (buf) {
                        if (buf.size() < 256 * 1024) {
                            buf.write(chunk, 0, n);
                        }
                    }
                }
            } catch (IOException e) {
                // the process was stopped
            }
        });
        try {
            boolean done = p.waitFor(seconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
            }
            reader.join(TimeUnit.SECONDS.toMillis(2));
            String out;
            synchronized (buf) {
                out = buf.toString(StandardCharsets.UTF_8);
            }
            return new Result(done ? p.exitValue() : -1, out);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static java.io.File nullFile() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    /** The first string value of {@code "name":"..."} in {@code json}, or null. */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        t = nl < 0 ? t : t.substring(0, nl);
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
