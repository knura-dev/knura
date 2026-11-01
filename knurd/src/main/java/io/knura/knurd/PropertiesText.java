package io.knura.knurd;

import java.util.ArrayList;
import java.util.List;

/**
 * Changes single settings in the text of a {@code .properties} file while keeping everything
 * else as it was: comments, blank lines, order and the settings it doesn't touch. {@link
 * java.util.Properties#store} would rewrite the whole file and drop every comment, which is no
 * way to treat a file people also edit by hand.
 *
 * <p>Files are read and written as UTF-8 (as knurd and knurgN read them), so values are written
 * as they are, with only backslashes, line breaks and leading spaces escaped.
 */
final class PropertiesText {
    private PropertiesText() {}

    /**
     * Sets {@code key} to {@code value}: the first line with that key is replaced, any later ones
     * are removed, and if there is none the setting is added at the end.
     */
    static String set(String text, String key, String value) {
        String line = escape(key, true) + "=" + escape(value, false);
        List<String> out = new ArrayList<>();
        boolean done = false;
        for (List<String> logical : logicalLines(text)) {
            if (key.equals(keyOf(logical))) {
                if (!done) {
                    out.add(line);
                    done = true;
                }
            } else {
                out.addAll(logical);
            }
        }
        if (!done) {
            while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
                out.remove(out.size() - 1);
            }
            out.add(line);
        }
        return String.join("\n", out) + "\n";
    }

    /** Removes every line that sets {@code key}. */
    static String remove(String text, String key) {
        List<String> out = new ArrayList<>();
        for (List<String> logical : logicalLines(text)) {
            if (!key.equals(keyOf(logical))) {
                out.addAll(logical);
            }
        }
        return out.isEmpty() ? "" : String.join("\n", out) + "\n";
    }

    /** The file's lines, with each setting's continuation lines kept together. */
    static List<List<String>> logicalLines(String text) {
        List<List<String>> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }
        String[] physical = text.split("\r\n|\r|\n", -1);
        int end = physical.length;
        if (end > 0 && physical[end - 1].isEmpty()) {
            end--; // the file's final line break
        }
        List<String> current = null;
        for (int i = 0; i < end; i++) {
            String line = physical[i];
            if (current == null) {
                current = new ArrayList<>();
            }
            current.add(line);
            boolean comment = current.size() == 1 && isComment(line);
            if (comment || !continues(line)) {
                lines.add(current);
                current = null;
            }
        }
        if (current != null) {
            lines.add(current);
        }
        return lines;
    }

    /** The key a logical line sets, or null for comments and blank lines. */
    static String keyOf(List<String> logical) {
        String first = logical.get(0);
        if (first.isBlank() || isComment(first)) {
            return null;
        }
        String s = first.stripLeading();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                key.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 'f' -> '\f';
                    default -> n;
                });
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                key.append(c);
            }
        }
        return key.toString();
    }

    private static boolean isComment(String line) {
        String s = line.stripLeading();
        return s.startsWith("#") || s.startsWith("!");
    }

    /** Whether a line ends in an odd number of backslashes, so the next line belongs to it. */
    private static boolean continues(String line) {
        int n = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            n++;
        }
        return n % 2 == 1;
    }

    static String escape(String s, boolean key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                case '=', ':', '#', '!' -> out.append(key ? "\\" + c : String.valueOf(c));
                case ' ' -> out.append(key || i == 0 ? "\\ " : " ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
