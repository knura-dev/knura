package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.PrivateFiles;
import io.knura.core.claim.ClaimCode;
import io.knura.core.crypto.Hex;
import io.knura.core.ntag424.FakeNtag424;
import io.knura.core.ntag424.Ntag424;
import io.knura.core.ntag424.TapUrlLayout;
import io.knura.core.provision.TagKeySet;
import io.knura.core.provision.TagProvisioner;
import io.knura.knurd.TapResult.Status;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A phone relaying knurd's commands to a (simulated) tag, through the admin API. */
class NfcRelayTest {
    /** NXP's public AN10922 example master key. Real keys never appear in this repository. */
    private static final byte[] MASTER = Hex.decode("00112233445566778899AABBCCDDEEFF");
    private static final byte[] UID = Hex.decode("04DE5F1EACC040");
    private static final String TAP_BASE = "https://desk.tail1234.ts.net/tap";
    private static final Duration REPLY_WAIT = Duration.ofSeconds(2);

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), java.time.ZoneOffset.UTC);
    private final TagKeys keys = TagKeys.derived(MASTER, Map.of());
    private String phone;
    private String laptop;
    private Path auditFile;
    private AuditLog audit;
    private AdminServer admin;

    @BeforeEach
    void start() throws Exception {
        phone = AdminTokens.generate(random);
        laptop = AdminTokens.generate(random);
        Path tokensFile = dir.resolve("tokens.properties");
        PrivateFiles.create(tokensFile, AdminTokens.format(Map.of(
                "fold7", AdminTokens.hash(phone), "laptop", AdminTokens.hash(laptop))));
        auditFile = dir.resolve("audit.log");
        audit = new AuditLog(auditFile, clock);
        admin = startAdmin(new TokenStore(tokensFile),
                new NfcRelay(keys, TAP_BASE, Set.of("fold7"), REPLY_WAIT));
    }

    private AdminServer startAdmin(TokenStore tokens, NfcRelay nfc) throws IOException {
        AdminServer server = new AdminServer("127.0.0.1", 0, null, tokens, audit, new MemoryCounterStore(),
                new RecentTaps(clock), new AdminServer.Info("master", null, null, 0, clock.instant()), clock, nfc);
        server.start();
        return server;
    }

    @AfterEach
    void stop() throws IOException {
        admin.close();
        audit.close();
    }

    @Test
    void provisionsAFactoryTag() throws Exception {
        FakeNtag424 tag = new FakeNtag424(UID);
        String done = relay(phone, "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\"}", tag);

        assertEquals("done", field(done, "status"), done);
        assertTrue(done.contains("\"tapUrl\":\"" + TAP_BASE + "?e="), done);
        assertArrayEquals(TagKeySet.knura(MASTER, UID).key(0), tag.key(0));
        TapVerifier knurd = new TapVerifier(keys, new MemoryCounterStore());
        assertEquals(Status.OK, knurd.verify(query(TagProvisioner.readTapUrl(new Ntag424(tag)))).status());
    }

    @Test
    void sendsTheStepsAsItGoes() throws Exception {
        FakeNtag424 tag = new FakeNtag424(UID);
        String first = post("/v1/nfc/sessions", phone, "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\"}").body();
        assertEquals("command", field(first, "status"), first);
        assertTrue(first.contains("Authenticating with the tag's current key 0"), first);
        StringBuilder all = new StringBuilder(first);
        String body = first;
        while ("command".equals(field(body, "status"))) {
            body = answer(field(body, "session"), phone, tag.transmit(Hex.decode(field(body, "command"))));
            all.append(body);
        }
        assertEquals(1, all.toString().split("Changing key 0", -1).length - 1, "each step is sent once");
    }

    @Test
    void infoTapAndReset() throws Exception {
        FakeNtag424 tag = new FakeNtag424(UID);
        String fresh = relay(phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}", tag);
        assertTrue(fresh.contains("\"keys\":\"factory\""), fresh);

        relay(phone, "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\"}", tag);
        String info = relay(phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}", tag);
        assertTrue(info.contains("\"keys\":\"knura\"") && info.contains("\"genuine\":true"), info);
        String tap = relay(phone, "{\"action\":\"tap\",\"uid\":\"04DE5F1EACC040\"}", tag);
        assertTrue(tap.contains("\"genuine\":true") && tap.contains("\"url\":\"" + TAP_BASE), tap);

        String reset = relay(phone, "{\"action\":\"reset\",\"uid\":\"04DE5F1EACC040\"}", tag);
        assertEquals("done", field(reset, "status"), reset);
        assertArrayEquals(new byte[16], tag.key(0));
        assertTrue(relay(phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}", tag).contains("\"keys\":\"factory\""));
    }

    @Test
    void aRunCutShortCanBeRunAgain() throws Exception {
        FakeNtag424 tag = new FakeNtag424(UID);
        tag.failOnChangeKey(2);
        String cut = relay(phone, "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\"}", tag);
        assertEquals("failed", field(cut, "status"), cut);
        assertTrue(cut.contains("could not reach the tag"), cut);
        assertArrayEquals(new byte[16], tag.key(0), "key 0 is changed last");

        String again = relay(phone, "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\"}", tag);
        assertEquals("done", field(again, "status"), again);
        assertArrayEquals(TagKeySet.knura(MASTER, UID).key(0), tag.key(0));
    }

    @Test
    void wrongFromIsExplained() throws Exception {
        String r = relay(phone, "{\"action\":\"reset\",\"uid\":\"04DE5F1EACC040\"}", new FakeNtag424(UID));
        assertEquals("failed", field(r, "status"), r);
        assertTrue(r.contains("not the Knura key"), r);
    }

    @Test
    void claimsAReadyToUseTag() throws Exception {
        ClaimCode claim = ClaimCode.of(Hex.decode("000102030405060708090A0B0C0D0E0F"));
        FakeNtag424 tag = claimTag(claim);
        String start = "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\",\"from\":\"claim\",\"claim\":\"%s\"}";

        String wrongCode = relay(phone, start.formatted(ClaimCode.of(new byte[16]).formatted()), tag);
        assertEquals("failed", field(wrongCode, "status"), wrongCode);
        assertTrue(wrongCode.contains("doesn't open with that claim code"), wrongCode);

        String done = relay(phone, start.formatted(claim.link(UID)), tag);
        assertEquals("done", field(done, "status"), done);
        assertArrayEquals(TagKeySet.knura(MASTER, UID).key(0), tag.key(0));
        assertFalse(Files.readString(auditFile).contains(claim.formatted().substring(0, 9)), "no claim code in the audit log");
    }

    @Test
    void aPackCardLinkClaimsAnyTagOfItsPack() throws Exception {
        ClaimCode claim = ClaimCode.of(Hex.decode("000102030405060708090A0B0C0D0E0F"));
        FakeNtag424 tag = claimTag(claim);
        String packLink = "knura://claim?code=" + claim.formatted().replace("-", "");
        String done = relay(phone, ("{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\",\"from\":\"claim\","
                + "\"claim\":\"%s\"}").formatted(packLink), tag);
        assertEquals("done", field(done, "status"), done);
        assertArrayEquals(TagKeySet.knura(MASTER, UID).key(0), tag.key(0));
    }

    @Test
    void checksTheClaimCardBeforeTheTag() throws Exception {
        ClaimCode claim = ClaimCode.of(Hex.decode("000102030405060708090A0B0C0D0E0F"));
        String start = "{\"action\":\"%s\",\"uid\":\"04DE5F1EACC040\",\"from\":\"claim\",\"claim\":\"%s\"}";
        String typo = claim.formatted().replace('4', '5');
        byte[] other = UID.clone();
        other[6] ^= 1;

        HttpResponse<String> r = post("/v1/nfc/sessions", phone, start.formatted("provision", typo));
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("typo"), r.body());
        r = post("/v1/nfc/sessions", phone, start.formatted("provision", claim.link(other)));
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("is for tag " + Hex.encode(other)), r.body());
        assertEquals(400, post("/v1/nfc/sessions", phone, start.formatted("info", claim.formatted())).statusCode());
        assertEquals(400, post("/v1/nfc/sessions", phone,
                "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\",\"from\":\"claim\"}").statusCode());
    }

    /** A tag the way a seller ships it: claim keys and a starter link. */
    private static FakeNtag424 claimTag(ClaimCode claim) throws IOException {
        FakeNtag424 tag = new FakeNtag424(UID);
        new TagProvisioner(line -> { }).provision(new Ntag424(tag), UID, TagKeySet.factory(), claim.keys(UID),
                TapUrlLayout.forBaseUrl("https://knura.io/new"));
        return tag;
    }

    @Test
    void aFakeTagReplyFails() throws Exception {
        String first = post("/v1/nfc/sessions", phone, "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\"}").body();
        String body = first;
        // A real select, then made-up answers to authentication.
        FakeNtag424 tag = new FakeNtag424(UID);
        body = answer(field(body, "session"), phone, tag.transmit(Hex.decode(field(body, "command"))));
        while ("command".equals(field(body, "status"))) {
            body = answer(field(body, "session"), phone, Hex.decode("00112233445566778899AABBCCDDEEFF91AF"));
        }
        assertEquals("failed", field(body, "status"), body);
        assertArrayEquals(new byte[16], tag.key(0));
    }

    @Test
    void onlyNamedTokensAndOnlyWhenOn() throws Exception {
        HttpResponse<String> other = post("/v1/nfc/sessions", laptop, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}");
        assertEquals(403, other.statusCode());
        assertTrue(other.body().contains("admin.provision.tokens"), other.body());
        assertEquals(401, post("/v1/nfc/sessions", null, "{}").statusCode());

        admin.close();
        admin = startAdmin(new TokenStore(dir.resolve("tokens.properties")), null);
        HttpResponse<String> off = post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}");
        assertEquals(403, off.statusCode());
        assertTrue(off.body().contains("is off"), off.body());
    }

    @Test
    void oneSessionAtATimeAndOnlyItsToken() throws Exception {
        String first = post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}").body();
        String session = field(first, "session");
        assertEquals(409, post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}").statusCode());

        admin.close();
        Path tokensFile = dir.resolve("tokens.properties");
        admin = startAdmin(new TokenStore(tokensFile), new NfcRelay(keys, TAP_BASE, Set.of("fold7", "laptop"), REPLY_WAIT));
        String mine = field(post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}").body(),
                "session");
        HttpResponse<String> theirs = post("/v1/nfc/sessions/" + mine, laptop, "{\"response\":\"9000\"}");
        assertEquals(404, theirs.statusCode(), "another token can't drive this session");
        assertEquals(404, post("/v1/nfc/sessions/" + session, phone, "{\"response\":\"9000\"}").statusCode());
    }

    @Test
    void givesUpWhenThePhoneGoesQuiet() throws Exception {
        String first = post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}").body();
        Thread.sleep(REPLY_WAIT.toMillis() + 1000);
        assertEquals(404, post("/v1/nfc/sessions/" + field(first, "session"), phone, "{\"response\":\"9000\"}").statusCode());
        String next = relay(phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}", new FakeNtag424(UID));
        assertEquals("done", field(next, "status"), "a new session can start");
    }

    @Test
    void badRequests() throws Exception {
        assertEquals(400, post("/v1/nfc/sessions", phone, "{\"action\":\"format\",\"uid\":\"04DE5F1EACC040\"}").statusCode());
        assertEquals(400, post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F\"}").statusCode());
        assertEquals(400, post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\",\"from\":\"x\"}")
                .statusCode());
        assertEquals(400, post("/v1/nfc/sessions", phone, "[1]").statusCode());
        assertEquals(413, post("/v1/nfc/sessions", phone, "{\"a\":\"" + "x".repeat(5000) + "\"}").statusCode());
        assertEquals(404, post("/v1/nfc/sessions/nope", phone, "{}").statusCode());
        HttpResponse<String> get = http.send(request("/v1/nfc/sessions", phone).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());

        String first = post("/v1/nfc/sessions", phone, "{\"action\":\"info\",\"uid\":\"04DE5F1EACC040\"}").body();
        String session = field(first, "session");
        assertEquals(400, post("/v1/nfc/sessions/" + session, phone, "{\"response\":\"zz\"}").statusCode());
        assertEquals(400, post("/v1/nfc/sessions/" + session, phone, "{}").statusCode());
    }

    @Test
    void auditsSessionsWithoutKeys() throws Exception {
        relay(phone, "{\"action\":\"provision\",\"uid\":\"04DE5F1EACC040\"}", new FakeNtag424(UID));
        String log = Files.readString(auditFile);
        assertTrue(log.contains("\"detail\":\"nfc provision 04DE5F1EACC040 done\""), log);
        assertTrue(log.contains("\"token\":\"fold7\""), log);
        TagKeySet knura = TagKeySet.knura(MASTER, UID);
        for (int slot = 0; slot < 5; slot++) {
            assertFalse(log.contains(Hex.encode(knura.key(slot))), "no key in the audit log");
        }
        assertFalse(log.contains(Hex.encode(MASTER)));
    }

    @Test
    void needsAMasterKeyAndAUsableTapBase() {
        assertThrows(IllegalArgumentException.class,
                () -> new NfcRelay(new TagKeys(new byte[16], new byte[16], Map.of()), TAP_BASE, Set.of("fold7"), REPLY_WAIT));
        assertThrows(IllegalArgumentException.class, () -> new NfcRelay(keys, "not a url", Set.of("fold7"), REPLY_WAIT));
    }

    /** Plays the phone: passes each command to the tag until the session ends; returns the last answer. */
    private String relay(String token, String start, FakeNtag424 tag) throws Exception {
        HttpResponse<String> r = post("/v1/nfc/sessions", token, start);
        assertEquals(200, r.statusCode(), r.body());
        String body = r.body();
        while ("command".equals(field(body, "status"))) {
            byte[] reply;
            try {
                reply = tag.transmit(Hex.decode(field(body, "command")));
            } catch (IOException e) {
                HttpResponse<String> lost = post("/v1/nfc/sessions/" + field(body, "session"), token,
                        "{\"error\":\"tag lost\"}");
                assertEquals(200, lost.statusCode(), lost.body());
                body = lost.body();
                continue;
            }
            body = answer(field(body, "session"), token, reply);
        }
        return body;
    }

    private String answer(String session, String token, byte[] reply) throws Exception {
        HttpResponse<String> r = post("/v1/nfc/sessions/" + session, token,
                "{\"response\":\"" + Hex.encode(reply) + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    @Test
    void phoneListsAndRemovesTokens() throws Exception {
        assertEquals("{\"you\":\"fold7\",\"tokens\":[\"fold7\",\"laptop\"]}", send("GET", "/v1/tokens", phone).body());
        assertEquals("{\"you\":\"laptop\",\"tokens\":[\"fold7\",\"laptop\"]}", send("GET", "/v1/tokens", laptop).body());

        assertEquals(403, send("DELETE", "/v1/tokens/fold7", laptop).statusCode(), "only phone tokens may remove");
        assertEquals(404, send("DELETE", "/v1/tokens/nobody", phone).statusCode());
        assertEquals(404, send("DELETE", "/v1/tokens/..%2Fx", phone).statusCode());
        assertEquals(405, send("GET", "/v1/tokens/laptop", phone).statusCode());

        HttpResponse<String> removed = send("DELETE", "/v1/tokens/laptop", phone);
        assertEquals(200, removed.statusCode());
        assertEquals("{\"removed\":\"laptop\"}", removed.body());
        assertEquals(401, send("GET", "/v1/whoami", laptop).statusCode(), "refused straight away");
        assertEquals("{\"you\":\"fold7\",\"tokens\":[\"fold7\"]}", send("GET", "/v1/tokens", phone).body());
        assertTrue(Files.readString(auditFile).contains("removed token laptop"));

        assertEquals(200, send("DELETE", "/v1/tokens/fold7", phone).statusCode(), "a phone may remove its own token");
        assertEquals(401, send("GET", "/v1/whoami", phone).statusCode());
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        return http.send(request(path, token).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(request(path, token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin.port() + path));
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], kv[1]);
        }
        return params;
    }
}
