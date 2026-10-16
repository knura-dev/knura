package io.knura.knurd;

/** Just enough JSON writing for knurd's small, fixed responses. */
final class Json {
    private Json() {}

    /** {@code s} as a JSON string, quotes included, or {@code null} for null. */
    static String quote(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
