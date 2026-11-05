package io.knura.knurd;

import static io.knura.knurd.Taps.ZERO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.admin.AdminTokens;
import io.knura.core.tap.TapSignature;
import io.knura.knurgn.Tapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Taps a paired phone signs and sends to {@code POST /tap}. */
class SignedTapTest {
    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final String QUERY = Taps.query(Taps.AN12196);

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> tokenHashes = new HashMap<>();
    private final List<String> heard = new ArrayList<>();
    private KnurdServer server;
    private TapKeys keys;
    private RecentTaps taps;
    private KeyPair everyday;
    private KeyPair confirm;
    private volatile KnurdServer.TapGate gate = (uid, tapper) -> true;

    @BeforeEach
    void start() throws Exception {
        tokenHashes.put("pixel", AdminTokens.hash(AdminTokens.generate(new java.security.SecureRandom())));
        keys = new TapKeys(dir.resolve(TapKeys.FILE), name -> Optional.ofNullable(tokenHashes.get(name)));
        everyday = p256();
        confirm = p256();
        keys.save("pixel", everyday.getPublic(), confirm.getPublic());
        TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), new MemoryCounterStore());
        KnurdServer.TapListener listener = new KnurdServer.TapListener() {
            @Override
            public int onVerifiedTap(String uid, int counter) {
                heard.add(uid + ":" + counter + " browser");
                return 1;
            }

            @Override
            public int onVerifiedTap(String uid, int counter, Tapper tapper) {
                heard.add(uid + ":" + counter + " " + tapper.phone() + (tapper.confirmed() ? " confirmed" : ""));
                return 2;
            }
        };
        server = new KnurdServer("127.0.0.1", 0, verifier, listener, null, Clock.fixed(NOW, ZoneOffset.UTC));
        taps = new RecentTaps(Clock.fixed(NOW, ZoneOffset.UTC));
        server.recordTapsTo(taps);
        server.acceptSignedTaps(keys);
        server.gateTapsWith((uid, tapper) -> gate.allows(uid, tapper));
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void aSignedTapRunsTheActionsAndSaysWhoTapped() throws Exception {
        HttpResponse<String> reply = post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY));
        assertEquals(200, reply.statusCode(), reply.body());
        assertEquals("{\"status\":\"ok\",\"uid\":\"04DE5F1EACC040\",\"counter\":61,\"actions\":2,"
                + "\"tapper\":{\"phone\":\"pixel\",\"confirmed\":false}}", reply.body());
        assertEquals(List.of("04DE5F1EACC040:61 pixel"), heard);
        assertEquals(new Tapper("pixel", false), taps.latest(1, e -> true).get(0).tapper());
    }

    @Test
    void theConfirmKeyMarksTheTapConfirmed() throws Exception {
        HttpResponse<String> reply = post(signed(confirm, "pixel", NOW.getEpochSecond(), TapSignature.Kind.CONFIRM));
        assertEquals(200, reply.statusCode(), reply.body());
        assertTrue(reply.body().contains("\"confirmed\":true"), reply.body());
        assertEquals(List.of("04DE5F1EACC040:61 pixel confirmed"), heard);
    }

    @Test
    void aSignedTapIsStillUsableOnlyOnce() throws Exception {
        String body = signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY);
        assertEquals(200, post(body).statusCode());
        HttpResponse<String> again = post(body);
        assertEquals(409, again.statusCode());
        assertEquals(1, heard.size());
    }

    @Test
    void aBadSignatureIsRefusedWithoutUsingTheTapUp() throws Exception {
        // Signed with the everyday key but claiming to be the confirm key: no fingerprint was asked for.
        HttpResponse<String> wrongKey = post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.CONFIRM));
        assertEquals(401, wrongKey.statusCode());
        assertTrue(wrongKey.body().contains("signature doesn't match"), wrongKey.body());
        // Another phone's key, and an unknown phone.
        assertEquals(401, post(signed(p256(), "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY)).statusCode());
        assertEquals(401, post(signed(everyday, "stranger", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY)).statusCode());
        assertTrue(heard.isEmpty());
        // The real tap still works afterwards: nothing used its counter.
        assertEquals(200, post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY)).statusCode());
    }

    @Test
    void anOldSignedTapIsRefused() throws Exception {
        HttpResponse<String> late = post(signed(everyday, "pixel", NOW.getEpochSecond() - 120, TapSignature.Kind.EVERYDAY));
        assertEquals(401, late.statusCode());
        assertTrue(late.body().contains("more than a minute"), late.body());
    }

    @Test
    void aRemovedPhoneCantSignTaps() throws Exception {
        tokenHashes.remove("pixel");
        assertEquals(401, post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY)).statusCode());
    }

    @Test
    void aNewPhoneUnderTheOldNameDoesntInheritItsKeys() throws Exception {
        tokenHashes.put("pixel", AdminTokens.hash(AdminTokens.generate(new java.security.SecureRandom())));
        assertFalse(keys.has("pixel"));
        assertEquals(401, post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY)).statusCode());
    }

    @Test
    void junkBodiesAreRefused() throws Exception {
        assertEquals(401, post("not json").statusCode());
        assertEquals(401, post("{}").statusCode());
        assertEquals(401, post("{\"link\":\"" + "a".repeat(3000) + "\"}").statusCode());
    }

    @Test
    void plainBrowserTapsStillWorkAndHaveNoTapper() throws Exception {
        HttpResponse<String> reply = http.send(HttpRequest.newBuilder(uri("/tap?" + QUERY)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reply.statusCode());
        assertEquals(List.of("04DE5F1EACC040:61 browser"), heard);
        assertNull(taps.latest(1, e -> true).get(0).tapper());
    }

    @Test
    void aTagForPhonesOnlyRefusesBrowserTapsWithoutUsingThemUp() throws Exception {
        gate = (uid, tapper) -> tapper != null;
        HttpResponse<String> reply = http.send(HttpRequest.newBuilder(uri("/tap?" + QUERY)).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, reply.statusCode());
        assertTrue(reply.body().contains("Use the Knura app for this tag"), reply.body());
        assertTrue(heard.isEmpty());
        assertEquals("refused", taps.latest(1, e -> true).get(0).status());
        // The whole query, URL-encoded (Map.of's order varies from run to run, so build it the same way).
        String handedOver = "href=\"intent://tap?q=" + java.net.URLEncoder.encode(QUERY, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(reply.body().contains(handedOver), "the page hands the tap to the app: " + reply.body());
        assertTrue(reply.body().contains("#Intent;scheme=knura;package=io.knura.android;end"), reply.body());
        // The refused tap didn't use the counter up, so the app can still send the same link signed, once.
        assertEquals(200, post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY)).statusCode());
        assertEquals(List.of("04DE5F1EACC040:61 pixel"), heard);
        HttpResponse<String> again = http.send(HttpRequest.newBuilder(uri("/tap?" + QUERY)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, again.statusCode(), "once used, the link is a replay for everyone");
    }

    @Test
    void aTagForPhonesOnlyRunsForASignedTap() throws Exception {
        gate = (uid, tapper) -> tapper != null;
        HttpResponse<String> reply = post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY));
        assertEquals(200, reply.statusCode(), reply.body());
        assertEquals(List.of("04DE5F1EACC040:61 pixel"), heard);
    }

    @Test
    void aPhoneTheTagDoesntListIsRefused() throws Exception {
        gate = (uid, tapper) -> tapper != null && tapper.phone().equals("tablet");
        HttpResponse<String> reply = post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY));
        assertEquals(403, reply.statusCode());
        assertTrue(reply.body().contains("\"status\":\"refused\""), reply.body());
        assertTrue(heard.isEmpty());
    }

    @Test
    void aBrokenGateRefusesRatherThanLettingEveryoneIn() throws Exception {
        gate = (uid, tapper) -> {
            throw new IllegalStateException("settings unreadable");
        };
        assertEquals(403, post(signed(everyday, "pixel", NOW.getEpochSecond(), TapSignature.Kind.EVERYDAY)).statusCode());
        assertTrue(heard.isEmpty());
    }

    @Test
    void keysFileIsOwnerOnlyAndReadsBack() throws Exception {
        Path file = dir.resolve(TapKeys.FILE);
        assertTrue(Files.exists(file));
        TapKeys again = new TapKeys(file, name -> Optional.ofNullable(tokenHashes.get(name)));
        assertTrue(again.find("pixel", TapSignature.Kind.CONFIRM).isPresent());
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    static KeyPair p256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String signed(KeyPair keys, String phone, long time, TapSignature.Kind kind) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(keys.getPrivate());
        s.update(TapSignature.message(QUERY, phone, time, kind));
        String sig = Base64.getEncoder().encodeToString(s.sign());
        return "{\"link\":\"" + QUERY + "\",\"phone\":\"" + phone + "\",\"time\":" + time + ",\"key\":\""
                + kind.jsonName() + "\",\"sig\":\"" + sig + "\"}";
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/tap")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
