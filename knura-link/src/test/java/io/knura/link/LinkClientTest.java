package io.knura.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Runs the client against a tiny WebSocket server on this computer. */
class LinkClientTest {
    private static final String TAP = "{\"type\":\"tap\",\"uid\":\"042D6B02936980\",\"counter\":5,\"tappedAt\":\"2026-10-26T22:51:11Z\"}";

    /** Answers one connection: checks the login, then sends {@code message} or refuses. */
    static final class Server implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final BlockingQueue<Map<String, String>> requests = new LinkedBlockingQueue<>();

        Server(int refuseWith, String message) throws IOException {
            Thread.ofVirtual().start(() -> {
                try (Socket client = socket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    Map<String, String> request = new HashMap<>();
                    request.put(":path", in.readLine().split(" ")[1]);
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                        int colon = line.indexOf(':');
                        request.put(line.substring(0, colon).toLowerCase(), line.substring(colon + 1).trim());
                    }
                    requests.add(request);
                    OutputStream out = client.getOutputStream();
                    if (refuseWith != 0) {
                        out.write(("HTTP/1.1 " + refuseWith + " Forbidden\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        return;
                    }
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                            (request.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                            + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    byte[] text = message.getBytes(StandardCharsets.UTF_8);
                    out.write(new byte[] {(byte) 0x81, (byte) text.length});
                    out.write(text);
                    out.write(new byte[] {(byte) 0x88, 2, 0x03, (byte) 0xE8});
                    out.flush();
                    Thread.sleep(500);
                } catch (Exception e) {
                    requests.add(Map.of(":error", e.toString()));
                }
            });
        }

        LinkConfig config() {
            return new LinkConfig(URI.create("ws://127.0.0.1:" + socket.getLocalPort() + "/link"),
                    PairingCodeTest.OWNER, PairingCodeTest.TOKEN, Path.of("knurgn.properties"));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void logsInWithTheTokenAndPassesTapsOn() throws Exception {
        BlockingQueue<String> taps = new LinkedBlockingQueue<>();
        try (Server server = new Server(0, TAP)) {
            LinkClient client = new LinkClient(server.config(), new TapRelay((uid, counter, at, name) -> {
                taps.add(uid + ":" + counter + "@" + at);
                return 1;
            }));
            Thread runner = Thread.ofVirtual().start(() -> {
                try {
                    client.run();
                } catch (Exception expected) {
                    // interrupted at the end of the test
                }
            });
            Map<String, String> request = server.requests.poll(10, TimeUnit.SECONDS);
            assertEquals("/link?owner=" + PairingCodeTest.OWNER, request.get(":path"));
            assertEquals("Bearer " + PairingCodeTest.TOKEN, request.get("authorization"));
            assertEquals("042D6B02936980:5@" + Instant.parse("2026-10-26T22:51:11Z"), taps.poll(10, TimeUnit.SECONDS));
            runner.interrupt();
        }
    }

    @Test
    void stopsWhenTheTokenIsRefused() throws Exception {
        try (Server server = new Server(403, null)) {
            LinkClient client = new LinkClient(server.config(), new TapRelay((uid, counter, at, name) -> 0));
            LinkClient.RefusedException e = assertThrows(LinkClient.RefusedException.class, client::run);
            assertTrue(e.getMessage().contains("knura-link pair"));
        }
    }
}
