package io.knura.knurctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.knura.core.resolver.OwnerRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** knurctl resolver register / check against a stand-in resolver on this computer. */
class ResolverCommandsTest {
    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private HttpServer resolver;
    private String resolverUrl;
    private Path config;
    /** The record the stand-in resolver holds, as JSON, or null. */
    private volatile String stored;

    @BeforeEach
    void start() throws IOException {
        resolver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resolver.createContext("/api/v1/owners/", this::handle);
        resolver.start();
        resolverUrl = "http://127.0.0.1:" + resolver.getAddress().getPort();
        config = dir.resolve("knurd.properties");
        assertEquals(0, run("init"));
        Files.writeString(config, "public.url=http://127.0.0.1:8424/\n", StandardOpenOption.APPEND);
    }

    @AfterEach
    void stop() {
        resolver.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String owner = exchange.getRequestURI().getPath().substring("/api/v1/owners/".length());
        int status;
        String body;
        if ("PUT".equals(exchange.getRequestMethod())) {
            String update = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            OwnerRecord record = OwnerRecord.fromJson("{\"owner\":\"" + owner + "\"," + update.substring(1));
            if (record.verify()) {
                stored = "{\"owner\":\"" + owner + "\"," + update.substring(1);
                status = 200;
                body = stored;
            } else {
                status = 403;
                body = "{\"error\":\"bad signature\"}";
            }
        } else if (stored != null) {
            status = 200;
            body = stored;
        } else {
            status = 404;
            body = "{\"error\":\"no such owner\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream o = exchange.getResponseBody()) {
            o.write(bytes);
        }
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        String[] all = new String[args.length + 2];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--config";
        all[args.length + 1] = dir.resolve("knurd.properties").toString();
        return Main.run(all, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Properties settings() throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(config)) {
            p.load(in);
        }
        return p;
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void registerCreatesAnOwnerThenRegistersIt() throws IOException {
        assertEquals(0, run("resolver", "register", "--resolver", resolverUrl));
        Properties p = settings();
        String owner = p.getProperty("resolver.owner");
        assertTrue(owner.matches("[a-km-np-z2-9]{10}"), owner);
        assertEquals(resolverUrl, p.getProperty("resolver.url"));
        assertEquals(64, p.getProperty("resolver.key").length());
        assertTrue(output().contains("restart knurd"), output());
        assertNull(stored, "the first run doesn't register yet: knurd must know its owner ID first");
        assertEquals(resolverUrl + "/" + owner, Main.defaultBaseUrl(p));

        assertEquals(0, run("resolver", "register"));
        OwnerRecord record = OwnerRecord.fromJson(stored);
        assertEquals(owner, record.owner());
        assertEquals("http://127.0.0.1:8424", record.server());
        assertTrue(record.verify());
        assertEquals(p.getProperty("resolver.publicKey"), record.publicKey());

        assertEquals(0, run("resolver", "check"));
        assertTrue(output().contains("All good"), output());

        // Registering again (e.g. after public.url changed) moves to a higher seq.
        assertEquals(0, run("resolver", "register", "--server", "http://127.0.0.1:9999"));
        OwnerRecord moved = OwnerRecord.fromJson(stored);
        assertTrue(moved.seq() > record.seq());
        assertEquals(1, run("resolver", "check"));
        assertTrue(output().contains("not to http://127.0.0.1:8424"), output());
    }

    @Test
    void checkCatchesARecordNotSignedByThisKey() throws IOException {
        assertEquals(0, run("resolver", "register", "--resolver", resolverUrl));
        assertEquals(0, run("resolver", "register"));
        OwnerRecord record = OwnerRecord.fromJson(stored);
        stored = "{\"owner\":\"" + record.owner() + "\",\"server\":\"https://evil.example.com\",\"seq\":"
                + (record.seq() + 1) + ",\"publicKey\":\"" + record.publicKey() + "\",\"signature\":\""
                + record.signature() + "\"}";
        assertEquals(1, run("resolver", "check"));
        assertTrue(output().contains("not signed by this owner key"), output());
    }

    @Test
    void registerRefusesBadInput() throws IOException {
        assertEquals(2, run("resolver"));
        assertEquals(2, run("resolver", "register", "--resolver", "http://tap.knura.io"));
        assertNull(settings().getProperty("resolver.owner"));
        assertEquals(2, run("resolver", "check"));

        assertEquals(0, run("resolver", "register", "--resolver", resolverUrl));
        assertEquals(2, run("resolver", "register", "--resolver", "https://other.example.com"));
        assertEquals(2, run("resolver", "register", "--server", "http://example.com"));
        assertNull(stored);
    }

    @Test
    void provisionDefaultsToPublicUrlWithoutAResolver() throws IOException {
        assertEquals("http://127.0.0.1:8424/tap", Main.defaultBaseUrl(settings()));
        assertEquals("http://127.0.0.1:8424/tap", Main.defaultBaseUrl(new Properties()));
    }
}
