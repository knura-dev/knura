package io.knura.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PairingCodeTest {
    static final String TOKEN = "knlink_" + "A".repeat(43);
    static final String OWNER = "ab3cd4ef5g";

    static String code(String url, String owner, String token) {
        return "knura://link?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8) + "&owner=" + owner + "&token=" + token;
    }

    @Test
    void readsTheDashboardsCode() {
        PairingCode code = PairingCode.parse("  " + code("wss://abc.example.com/link", OWNER, TOKEN) + "\n");
        assertEquals("wss://abc.example.com/link", code.url().toString());
        assertEquals(OWNER, code.owner());
        assertEquals(TOKEN, code.token());
        assertFalse(code.toString().contains(TOKEN), "toString hides the token");
    }

    @Test
    void refusesUnencryptedAddresses() {
        assertThrows(IllegalArgumentException.class, () -> PairingCode.parse(code("ws://abc.example.com/link", OWNER, TOKEN)));
        assertThrows(IllegalArgumentException.class, () -> PairingCode.parse(code("https://abc.example.com/link", OWNER, TOKEN)));
        PairingCode.parse(code("ws://127.0.0.1:9000/link", OWNER, TOKEN));
    }

    @Test
    void neverQuotesTheTokenInErrors() {
        String bad = "knlink_" + "A".repeat(10);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PairingCode.parse(code("wss://abc.example.com/link", OWNER, bad)));
        assertFalse(e.getMessage().contains(bad));
    }

    @Test
    void refusesOtherCodes() {
        assertThrows(IllegalArgumentException.class, () -> PairingCode.parse("knura://pair?url=x&token=knurd_x"));
        assertThrows(IllegalArgumentException.class, () -> PairingCode.parse("knura://link?url=wss%3A%2F%2Fa.b%2Flink&owner=" + OWNER));
        assertThrows(IllegalArgumentException.class, () -> PairingCode.parse(code("wss://abc.example.com/link", "nope", TOKEN)));
    }
}
