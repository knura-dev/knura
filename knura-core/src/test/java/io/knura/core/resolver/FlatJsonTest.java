package io.knura.core.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlatJsonTest {
    @Test
    void readsFlatObjects() {
        Map<String, Object> expected = new HashMap<>();
        expected.put("a", "x\"y\\z/\n\u00e9");
        expected.put("n", -12L);
        expected.put("t", true);
        expected.put("f", false);
        expected.put("z", null);
        assertEquals(expected, FlatJson.parse(
                " { \"a\" : \"x\\\"y\\\\z\\/\\n\\u00e9\", \"n\":-12,\"t\":true,\"f\":false,\"z\":null } "));
        assertEquals(Map.of(), FlatJson.parse("{}"));
    }

    @Test
    void refusesEverythingElse() {
        for (String bad : new String[] {
            "", "[]", "{", "{\"a\":1", "{\"a\":1}x", "{\"a\":{}}", "{\"a\":[1]}", "{\"a\":1.5}",
            "{\"a\":1,\"a\":2}", "{a:1}", "{\"a\":\"\\q\"}", "{\"a\":\"\\u12\"}", "{\"a\":99999999999999999999}",
        }) {
            assertThrows(IllegalArgumentException.class, () -> FlatJson.parse(bad), bad);
        }
    }
}
