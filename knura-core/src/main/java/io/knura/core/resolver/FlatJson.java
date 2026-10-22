package io.knura.core.resolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads one flat JSON object whose values are strings, whole numbers, true, false or null: enough
 * for the resolver's answers without a JSON library. Nested objects and arrays are refused.
 */
public final class FlatJson {
    private final String text;
    private int pos;

    private FlatJson(String text) {
        this.text = text;
    }

    /** Throws {@link IllegalArgumentException} if {@code json} is not such an object. */
    public static Map<String, Object> parse(String json) {
        FlatJson p = new FlatJson(json);
        Map<String, Object> fields = new HashMap<>();
        p.skipSpace();
        p.expect('{');
        p.skipSpace();
        if (p.peek() == '}') {
            p.pos++;
        } else {
            while (true) {
                p.skipSpace();
                String name = p.string();
                p.skipSpace();
                p.expect(':');
                p.skipSpace();
                if (fields.put(name, p.value()) != null) {
                    throw new IllegalArgumentException("JSON repeats field " + name);
                }
                p.skipSpace();
                if (p.peek() == ',') {
                    p.pos++;
                } else {
                    p.expect('}');
                    break;
                }
            }
        }
        p.skipSpace();
        if (p.pos != json.length()) {
            throw new IllegalArgumentException("unexpected text after the JSON object");
        }
        return fields;
    }

    private Object value() {
        char ch = peek();
        if (ch == '"') {
            return string();
        }
        if (ch == '-' || (ch >= '0' && ch <= '9')) {
            int start = pos;
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            try {
                return Long.parseLong(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number in JSON", e);
            }
        }
        for (String word : new String[] {"true", "false", "null"}) {
            if (text.startsWith(word, pos)) {
                pos += word.length();
                return word.equals("null") ? null : Boolean.valueOf(word);
            }
        }
        throw new IllegalArgumentException("unsupported JSON value at " + pos);
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char ch = next();
            if (ch == '"') {
                return out.toString();
            }
            if (ch < 0x20) {
                throw new IllegalArgumentException("control character in JSON string");
            }
            if (ch != '\\') {
                out.append(ch);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"', '\\', '/' -> out.append(esc);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw new IllegalArgumentException("bad \\u escape in JSON");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("bad \\u escape in JSON", e);
                    }
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("bad escape in JSON");
            }
        }
    }

    private void skipSpace() {
        while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("JSON ends too early");
        }
        return text.charAt(pos);
    }

    private char next() {
        char ch = peek();
        pos++;
        return ch;
    }

    private void expect(char ch) {
        if (next() != ch) {
            throw new IllegalArgumentException("expected '" + ch + "' in JSON at " + (pos - 1));
        }
    }
}
