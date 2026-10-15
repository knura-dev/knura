package io.knura.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AdminTokensTest {
    @Test
    void generatedTokensAreWellFormedAndDiffer() {
        SecureRandom random = new SecureRandom();
        String a = AdminTokens.generate(random);
        String b = AdminTokens.generate(random);
        assertTrue(AdminTokens.isWellFormed(a), a);
        assertEquals(49, a.length());
        assertNotEquals(a, b);
        assertFalse(AdminTokens.isWellFormed("knurd_short"));
        assertFalse(AdminTokens.isWellFormed(a + "x"));
        assertFalse(AdminTokens.isWellFormed(null));
    }

    @Test
    void hashIsSha256OfTheToken() {
        // printf knurd_test | sha256sum
        assertEquals("sha256:c6560d1b2e989cde5ef02101f63117cfc73e6c5965505fdaa8e01ddc0e65e16c", AdminTokens.hash("knurd_test"));
        assertEquals(AdminTokens.hash("knurd_test"), AdminTokens.hash("knurd_test"));
        assertNotEquals(AdminTokens.hash("knurd_test"), AdminTokens.hash("knurd_tesT"));
        assertTrue(AdminTokens.sameHash(AdminTokens.hash("x"), AdminTokens.hash("x")));
        assertFalse(AdminTokens.sameHash(AdminTokens.hash("x"), AdminTokens.hash("y")));
    }

    @Test
    void tokensFileRoundTrips() throws Exception {
        Map<String, String> tokens = Map.of("laptop", AdminTokens.hash("a"), "loomstudio", AdminTokens.hash("b"));
        Properties p = new Properties();
        p.load(new StringReader(AdminTokens.format(tokens)));
        assertEquals(tokens, AdminTokens.parse(p));
        assertFalse(AdminTokens.format(tokens).contains("knurd_"), "the file never holds tokens");
    }

    @Test
    void tokensFileRejectsDamage() {
        Properties badName = new Properties();
        badName.setProperty("Laptop!", AdminTokens.hash("a"));
        assertThrows(IllegalArgumentException.class, () -> AdminTokens.parse(badName));
        Properties badHash = new Properties();
        badHash.setProperty("laptop", "knurd_plaintexttoken");
        assertThrows(IllegalArgumentException.class, () -> AdminTokens.parse(badHash));
    }

    @Test
    void names() {
        assertTrue(AdminTokens.isValidName("laptop"));
        assertTrue(AdminTokens.isValidName("loom-studio_2"));
        assertFalse(AdminTokens.isValidName(""));
        assertFalse(AdminTokens.isValidName("-x"));
        assertFalse(AdminTokens.isValidName("Laptop"));
        assertFalse(AdminTokens.isValidName("a".repeat(33)));
    }
}
