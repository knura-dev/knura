package io.knura.knurctl;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Asks the person at the keyboard for a line or a secret (passphrases are never echoed). */
interface Prompter {
    /** The line typed, or null at end of input. */
    String line(String prompt) throws IOException;

    /** The secret typed, not shown on screen, or null at end of input. */
    char[] secret(String prompt) throws IOException;

    /** The terminal when there is one, else lines on standard input (for scripts). */
    static Prompter system(PrintStream out) {
        Console console = System.console();
        if (console != null) {
            return new Prompter() {
                @Override
                public String line(String prompt) {
                    return console.readLine("%s", prompt);
                }

                @Override
                public char[] secret(String prompt) {
                    return console.readPassword("%s", prompt);
                }
            };
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new Prompter() {
            @Override
            public String line(String prompt) throws IOException {
                out.print(prompt);
                out.flush();
                return in.readLine();
            }

            @Override
            public char[] secret(String prompt) throws IOException {
                String line = line(prompt);
                return line == null ? null : line.toCharArray();
            }
        };
    }

    /** For callers with nobody to ask: every question gets end of input. */
    static Prompter none() {
        return new Prompter() {
            @Override
            public String line(String prompt) {
                return null;
            }

            @Override
            public char[] secret(String prompt) {
                return null;
            }
        };
    }
}
