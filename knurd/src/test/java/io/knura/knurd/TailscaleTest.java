package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The setup page's Tailscale helper, against a stand-in {@code tailscale} script. */
class TailscaleTest {
    @TempDir
    Path dir;

    private Path script;

    @BeforeEach
    void needsAShell() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "uses a shell script as tailscale");
        script = dir.resolve("tailscale");
    }

    private Tailscale fake(String body) throws Exception {
        Files.writeString(script, "#!/bin/sh\nD=" + dir + "\n" + body);
        assertTrue(script.toFile().setExecutable(true));
        return new Tailscale(List.of(script.toString()), 8424, 3);
    }

    private static final String RUNNING = """
            case "$1 $2" in
              "status --json") printf '{"BackendState":"Running","AuthURL":"","Self":{"DNSName":"knura-home.tail8c2f4.ts.net.","Online":true},"CertDomains":["knura-home.tail8c2f4.ts.net"],"Peer":{"a":{"DNSName":"other.tail8c2f4.ts.net."}}}' ;;
              "funnel status") if [ -f "$D/on" ]; then printf '{"Web":{"knura-home.tail8c2f4.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:8424"}}}},"AllowFunnel":{"knura-home.tail8c2f4.ts.net:443":true}}'; else echo '{}'; fi ;;
            """;

    @Test
    void notInstalled() {
        Tailscale.Status s = new Tailscale(null, 8424).status();
        assertEquals("NotInstalled", s.state());
        assertNull(s.url());
        assertEquals("https://tailscale.com/download", new Tailscale(null, 8424).turnOnFunnel().fixUrl());
    }

    @Test
    void readsThisComputersNameNotAPeers() throws Exception {
        Tailscale.Status s = fake(RUNNING + "esac\n").status();
        assertEquals("Running", s.state());
        assertEquals("knura-home.tail8c2f4.ts.net", s.dnsName());
        assertEquals("https://knura-home.tail8c2f4.ts.net", s.url());
        assertTrue(s.httpsCerts());
        assertFalse(s.funnel());
    }

    @Test
    void needsLoginShowsTheLink() throws Exception {
        Tailscale.Status s = fake("printf '{\"BackendState\":\"NeedsLogin\",\"AuthURL\":\"https://login.tailscale.com/a/abc123\",\"Self\":{\"DNSName\":\"\"}}'\n")
                .status();
        assertEquals("NeedsLogin", s.state());
        assertEquals("https://login.tailscale.com/a/abc123", s.loginUrl());
        assertNull(s.dnsName());
    }

    @Test
    void funnelNeedsOperatorPermissionThenTurnsOn() throws Exception {
        Tailscale t = fake(RUNNING + """
                  "funnel --bg")
                    if [ "$3" != "8424" ]; then echo "wrong port $3"; exit 3; fi
                    if [ -f "$D/once" ]; then touch "$D/on"; echo ok; exit 0; fi
                    touch "$D/once"; echo "Access denied: serve config denied"; exit 1 ;;
                esac
                """);
        Tailscale.Funnel first = t.turnOnFunnel();
        assertFalse(first.on());
        assertEquals("sudo tailscale set --operator=" + Tailscale.operator(), first.fixCommand());
        assertTrue(t.turnOnFunnel().on());
        assertTrue(t.status().funnel(), "funnel status shows it passing to the tap port");
    }

    @Test
    void funnelNotAllowedOnTheTailnetGivesTheLinkAndDoesNotHang() throws Exception {
        Tailscale t = fake(RUNNING + """
                  "funnel --bg") echo "Funnel is not enabled on your tailnet."; echo "To enable, visit:"; echo "  https://login.tailscale.com/f/funnel?node=nXYZ"; sleep 60 ;;
                esac
                """);
        long start = System.nanoTime();
        Tailscale.Funnel f = t.turnOnFunnel();
        assertFalse(f.on());
        assertEquals("https://login.tailscale.com/f/funnel?node=nXYZ", f.fixUrl());
        assertTrue(System.nanoTime() - start < 15_000_000_000L, "gave up waiting");
    }
}
