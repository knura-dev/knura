package io.knura.knurctl;

import io.knura.core.crypto.Hex;
import io.knura.core.ntag424.Transceiver;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;

/** A tag on a PC/SC reader (such as the ACR122U), through javax.smartcardio. */
final class PcscReader implements Transceiver, AutoCloseable {
    /** PC/SC "Get Data" pseudo-APDU: the UID the reader saw during anticollision. */
    private static final byte[] GET_UID = {(byte) 0xFF, (byte) 0xCA, 0x00, 0x00, 0x00};

    private final Card card;
    private final PrintStream trace;

    private PcscReader(Card card, PrintStream trace) {
        this.card = card;
        this.trace = trace;
    }

    /** Every reader PC/SC knows about. */
    static List<CardTerminal> readers() throws ReaderException {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            return factory.terminals().list();
        } catch (CardException | RuntimeException e) {
            throw new ReaderException(explain(e), e);
        }
    }

    /**
     * Waits for a tag on the chosen reader and connects to it.
     *
     * @param selector reader number (from {@code knurctl readers}) or part of its name, or null
     *     for the only reader
     * @param trace where to print every APDU, or null
     */
    static PcscReader connect(String selector, Duration wait, PrintStream out, PrintStream trace)
            throws ReaderException {
        CardTerminal terminal = choose(readers(), selector);
        out.println("Reader: " + terminal.getName());
        try {
            if (!terminal.isCardPresent()) {
                out.println("Put a tag on the reader...");
                if (!terminal.waitForCardPresent(wait.toMillis())) {
                    throw new ReaderException("no tag showed up within " + wait.toSeconds() + " seconds");
                }
            }
            Card card = terminal.connect("*");
            if (trace != null) {
                trace.println("  ATR " + Hex.encode(card.getATR().getBytes()));
            }
            return new PcscReader(card, trace);
        } catch (CardException e) {
            throw new ReaderException(explain(e), e);
        }
    }

    private static CardTerminal choose(List<CardTerminal> readers, String selector) throws ReaderException {
        if (readers.isEmpty()) {
            throw new ReaderException("no NFC reader found: is it plugged in, and is pcscd running?");
        }
        if (selector == null) {
            if (readers.size() > 1) {
                throw new ReaderException(
                        "more than one reader is connected; pick one with --reader (see knurctl readers)");
            }
            return readers.get(0);
        }
        try {
            int index = Integer.parseInt(selector);
            if (index >= 1 && index <= readers.size()) {
                return readers.get(index - 1);
            }
        } catch (NumberFormatException e) {
            // Not a number: match by name below.
        }
        for (CardTerminal t : readers) {
            if (t.getName().toLowerCase().contains(selector.toLowerCase())) {
                return t;
            }
        }
        throw new ReaderException("no reader matches \"" + selector + "\" (see knurctl readers)");
    }

    /**
     * Sends raw bytes. {@code CommandAPDU} would reject NTAG 424 native commands, so this goes
     * through the ByteBuffer overload, which passes bytes through unchanged.
     */
    @Override
    public byte[] transmit(byte[] command) throws IOException {
        if (trace != null) {
            trace.println("  > " + Hex.encode(command));
        }
        ByteBuffer response = ByteBuffer.allocate(512);
        try {
            int n = card.getBasicChannel().transmit(ByteBuffer.wrap(command), response);
            byte[] out = Arrays.copyOf(response.array(), n);
            if (trace != null) {
                trace.println("  < " + Hex.encode(out));
            }
            return out;
        } catch (CardException e) {
            throw new IOException(explain(e), e);
        }
    }

    /** The UID from anticollision, which needs no key. */
    byte[] uid() throws IOException {
        byte[] r = transmit(GET_UID);
        if (r.length != 9 || r[7] != (byte) 0x90 || r[8] != 0x00) {
            throw new IOException("the reader could not report the tag's UID (got " + Hex.encode(r) + ")");
        }
        return Arrays.copyOf(r, 7);
    }

    @Override
    public void close() {
        try {
            card.disconnect(false);
        } catch (CardException e) {
            // The tag may already be gone; nothing to clean up.
        }
    }

    private static String explain(Exception e) {
        String m = String.valueOf(e.getMessage());
        Throwable cause = e.getCause();
        String all = m + (cause == null ? "" : " " + cause.getMessage());
        if (all.contains("SCARD_E_NO_SERVICE")) {
            return "the PC/SC service is not running; start it with: sudo systemctl enable --now pcscd.socket";
        }
        if (all.contains("SCARD_E_NO_READERS_AVAILABLE")) {
            return "no NFC reader found: is it plugged in, and is pcscd running?";
        }
        if (all.contains("SCARD_W_REMOVED_CARD") || all.contains("SCARD_E_NO_SMARTCARD")) {
            return "the tag moved off the reader; hold it still on the reader and try again";
        }
        if (e instanceof IllegalStateException || all.contains("PC/SC not available") || all.contains("libpcsclite")) {
            return "Java could not load the PC/SC library; install pcsc-lite (Fedora: sudo dnf install pcsc-lite pcsc-lite-ccid)";
        }
        return "reader error: " + all;
    }

    /** Anything that stops knurctl from reaching a tag. */
    static final class ReaderException extends Exception {
        private static final long serialVersionUID = 1L;

        ReaderException(String message) {
            super(message);
        }

        ReaderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
