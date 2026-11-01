package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class PropertiesTextTest {
    private static final String FILE = """
            # knurd settings
            master.key=00112233445566778899AABBCCDDEEFF

            ! another comment
            public.url = https://desk.example
            long.value=one \\
                two
            """;

    @Test
    void replacesInPlaceAndKeepsEverythingElse() {
        String out = PropertiesText.set(FILE, "public.url", "https://new.example");
        assertEquals(FILE.replace("public.url = https://desk.example", "public.url=https://new.example"), out);
    }

    @Test
    void addsAtTheEnd() {
        String out = PropertiesText.set(FILE, "knura.name", "Our house");
        assertEquals(FILE + "knura.name=Our house\n", out);
        assertEquals("a=1\nb=2\n", PropertiesText.set("a=1\n\n\n", "b", "2"));
        assertEquals("b=2\n", PropertiesText.set("", "b", "2"));
    }

    @Test
    void removesContinuationLinesToo() {
        String out = PropertiesText.remove(FILE, "long.value");
        assertEquals(FILE.replace("long.value=one \\\n    two\n", ""), out);
    }

    @Test
    void dropsLaterDuplicates() {
        assertEquals("a=3\nb=2\n", PropertiesText.set("a=1\nb=2\na=9\n", "a", "3"));
    }

    @Test
    void valuesReadBackAsWritten() throws IOException {
        for (String value : new String[] {"Our house", " leading space", "back\\slash", "a=b:c#d!e", "Café 🏠", "line\nbreak"}) {
            Properties p = new Properties();
            p.load(new StringReader(PropertiesText.set("x=1\n", "knura.name", value)));
            assertEquals(value, p.getProperty("knura.name"), value);
        }
        Properties p = new Properties();
        p.load(new StringReader(PropertiesText.set("", "odd key=x", "v")));
        assertEquals("v", p.getProperty("odd key=x"));
    }

    @Test
    void commentsEndingInABackslashDontSwallowTheNextLine() {
        assertEquals("# note \\\na=2\n", PropertiesText.set("# note \\\na=1\n", "a", "2"));
    }
}
