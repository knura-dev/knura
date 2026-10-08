package io.knura.core.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResolverClientTest {
    private static final OwnerKey KEY = OwnerKey.load(OwnerRecordTest.SEED, OwnerRecordTest.PUBLIC_KEY);
    private static final String OWNER = OwnerRecordTest.OWNER;
    private static final String SERVER = OwnerRecordTest.SERVER;

    private HttpServer server;
    private ResolverClient client;
    private final AtomicReference<String> putBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/owners/", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                putBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new ResolverClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetchesRecords() throws IOException {
        OwnerRecord record = OwnerRecord.sign(OWNER, SERVER, 5, KEY);
        answer = "{\"owner\":\"" + OWNER + "\"," + record.updateJson().substring(1);
        assertEquals(Optional.of(record), client.fetch(OWNER));

        status = 404;
        answer = "{\"error\":\"no such owner\"}";
        assertEquals(Optional.empty(), client.fetch(OWNER));

        status = 500;
        IOException e = assertThrows(IOException.class, () -> client.fetch(OWNER));
        assertTrue(e.getMessage().contains("HTTP 500"));
    }

    @Test
    void putsSignedUpdatesAndReportsRefusals() throws IOException {
        OwnerRecord record = OwnerRecord.sign(OWNER, SERVER, 5, KEY);
        answer = "{}";
        client.put(record);
        assertEquals(record.updateJson(), putBody.get());

        status = 422;
        answer = "{\"error\":\"does not name owner " + OWNER + "\"}";
        IOException e = assertThrows(IOException.class, () -> client.put(record));
        assertTrue(e.getMessage().endsWith("HTTP 422: does not name owner " + OWNER), e.getMessage());
    }

    @Test
    void resolverUrlMustBeHttps() {
        assertEquals("https://tap.knura.io/" + OWNER, new ResolverClient(ResolverClient.DEFAULT_URL).tapBase(OWNER));
        assertFalse(new ResolverClient(ResolverClient.DEFAULT_URL).isLocal());
        assertTrue(client.isLocal());
        assertThrows(IllegalArgumentException.class, () -> new ResolverClient("http://tap.knura.io"));
        assertThrows(IllegalArgumentException.class, () -> new ResolverClient("https://tap.knura.io?x=1"));
        assertThrows(IllegalArgumentException.class, () -> client.fetch("../../etc"));
    }

    @Test
    void checksRecordsAgainstThisServer() {
        String pub = KEY.publicKeyHex();
        OwnerRecord good = OwnerRecord.sign(OWNER, SERVER, 5, KEY);
        assertEquals(ResolverCheck.Status.OK, ResolverCheck.check(Optional.of(good), OWNER, pub, SERVER).status());
        assertEquals(ResolverCheck.Status.NOT_REGISTERED,
                ResolverCheck.check(Optional.empty(), OWNER, pub, SERVER).status());
        assertEquals(ResolverCheck.Status.OTHER_SERVER,
                ResolverCheck.check(Optional.of(good), OWNER, pub, "https://new.example.com").status());

        OwnerKey other = OwnerKey.generate();
        OwnerRecord otherKey = OwnerRecord.sign(OWNER, "https://evil.example.com", 6, other);
        assertEquals(ResolverCheck.Status.OTHER_KEY,
                ResolverCheck.check(Optional.of(otherKey), OWNER, pub, SERVER).status());

        // The resolver's store was changed without the owner's key.
        OwnerRecord tampered = new OwnerRecord(OWNER, "https://evil.example.com", 6, pub, good.signature());
        ResolverCheck.Result result = ResolverCheck.check(Optional.of(tampered), OWNER, pub, SERVER);
        assertEquals(ResolverCheck.Status.BAD_SIGNATURE, result.status());
        assertEquals("https://evil.example.com", result.server());
    }

    @Test
    void explainsConnectionFailuresInWords() {
        java.net.ConnectException noName = new java.net.ConnectException();
        noName.initCause(new java.nio.channels.UnresolvedAddressException());
        assertTrue(ResolverClient.reason(noName).contains("DNS"));
        assertTrue(ResolverClient.reason(new java.net.ConnectException()).equals("ConnectException"));
        IOException wrapped = new IOException(null, new java.net.ConnectException("Connection refused"));
        assertEquals("Connection refused", ResolverClient.reason(wrapped));
        assertTrue(ResolverClient.reason(new javax.net.ssl.SSLHandshakeException("bad cert")).contains("certificate"));
    }

    @Test
    void unreachableResolverSaysWhy() {
        ResolverClient nowhere = new ResolverClient("https://does-not-exist.invalid");
        IOException e = assertThrows(IOException.class, () -> nowhere.fetch(OWNER));
        assertFalse(e.getMessage().endsWith("null"), e.getMessage());
    }
}
