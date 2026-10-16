package io.knura.knurctl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON knurd's admin API answers with into maps, lists, strings, longs, booleans and
 * nulls. Small on purpose: knurctl has no dependencies beyond the JDK.
 */
final class MiniJson {
    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    /** Throws {@link IllegalArgumentException} if {@code json} isn't valid JSON of that kind. */
    static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        Object value = p.value(0);
        p.space();
        if (p.pos != json.length()) {
            throw new IllegalArgumentException("unexpected text after JSON");
        }
        return value;
    }

    private Object value(int depth) {
        if (depth > 16) {
            throw new IllegalArgumentException("JSON nested too deeply");
        }
        space();
        if (pos >= text.length()) {
            throw new IllegalArgumentException("JSON ends early");
        }
        char ch = text.charAt(pos);
        switch (ch) {
            case '{' -> {
                pos++;
                Map<String, Object> map = new LinkedHashMap<>();
                space();
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    map.put(key, value(depth + 1));
                    space();
                    if (peek() == ',') {
                        pos++;
                    } else {
                        expect('}');
                        return map;
                    }
                }
            }
            case '[' -> {
                pos++;
                List<Object> list = new ArrayList<>();
                space();
                if (peek() == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(value(depth + 1));
                    space();
                    if (peek() == ',') {
                        pos++;
                    } else {
                        expect(']');
                        return list;
                    }
                }
            }
            case '"' -> {
                return string();
            }
            default -> {
                for (String word : new String[] {"true", "false", "null"}) {
                    if (text.startsWith(word, pos)) {
                        pos += word.length();
                        return word.equals("null") ? null : Boolean.valueOf(word);
                    }
                }
                int start = pos;
                if (peek() == '-') {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == start || (pos == start + 1 && text.charAt(start) == '-')) {
                    throw new IllegalArgumentException("unexpected character in JSON at " + start);
                }
                return Long.parseLong(text.substring(start, pos));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unterminated JSON string");
            }
            char ch = text.charAt(pos++);
            if (ch == '"') {
                return out.toString();
            }
            if (ch != '\\') {
                out.append(ch);
                continue;
            }
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unterminated JSON string");
            }
            char esc = text.charAt(pos++);
            switch (esc) {
                case '"', '\\', '/' -> out.append(esc);
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw new IllegalArgumentException("bad \\u escape in JSON");
                    }
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("bad escape in JSON");
            }
        }
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void expect(char ch) {
        if (peek() != ch) {
            throw new IllegalArgumentException("expected '" + ch + "' in JSON at " + pos);
        }
        pos++;
    }

    private void space() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}
