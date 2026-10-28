package io.knura.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.admin.AdminTokens;
import io.knura.core.claim.ClaimCode;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class RedactTest {
    @Test
    void hidesTokens() {
        String token = AdminTokens.generate(new SecureRandom());
        String line = Redact.scrub("login with " + token + " failed");
        assertFalse(line.contains(token.substring(6)), line);
        assertEquals("login with [hidden] failed", line);
        assertEquals("[hidden]", Redact.scrub("knlink_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"));
        assertEquals("secret [hidden]", Redact.scrub("secret whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"));
        assertEquals("Authorization: [hidden]", Redact.scrub("Authorization: Bearer eyJhbGciOi.abc.def"));
    }

    @Test
    void hidesClaimCodesWithOrWithoutDashes() {
        ClaimCode code = ClaimCode.random(new SecureRandom());
        assertEquals("claim [hidden] used", Redact.scrub("claim " + code.formatted() + " used"));
        assertEquals("claim [hidden] used", Redact.scrub("claim " + code.formatted().replace("-", "") + " used"));
    }

    @Test
    void hidesKeysAndKeySettings() {
        assertEquals("master.key=[hidden]", Redact.scrub("master.key=00112233445566778899AABBCCDDEEFF"));
        assertEquals("loaded [hidden]", Redact.scrub("loaded 00112233445566778899aabbccddeeff"));
        assertEquals("resolver.key=[hidden] next", Redact.scrub("resolver.key=abc123 next"));
        assertEquals("password: [hidden]", Redact.scrub("password: hunter2"));
        assertEquals("secret=[hidden]", Redact.scrub("secret=s3"));
    }

    @Test
    void hidesTapLinkMacsAndEncryptedData() {
        String url = "GET /tap?e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086";
        assertEquals("GET /tap?e=[hidden]&c=[hidden]", Redact.scrub(url));
        assertEquals("/tap?uid=04DE5F1EACC040&ctr=00003D&c=[hidden]",
                Redact.scrub("/tap?uid=04DE5F1EACC040&ctr=00003D&c=94EED9EE65337086"));
    }

    @Test
    void keepsWhatTroubleshootingNeeds() {
        String[] lines = {
            "tap ok uid=04DE5F1EACC040 counter=61 (8 ms)",
            "tap 04DE5F1EACC040:61 knot=front-door action=living-room: delivered after 1 attempt(s) (HTTP 200)",
            "tap rejected uid=- counter=0: MAC does not match",
            "couldn't reach the hosted account (ConnectException); trying again",
            "tap 04ABCDEF12345C:12 came twice; ran it once",
        };
        for (String line : lines) {
            assertEquals(line, Redact.scrub(line));
        }
        assertEquals(null, Redact.scrub(null));
        assertTrue(Redact.scrub("").isEmpty());
    }
}
