package io.knura.core.ntag424;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
import java.util.ArrayDeque;
import java.util.Deque;

/** Replays a recorded exchange: each command sent must equal the recorded one, byte for byte. */
final class ScriptedTransceiver implements Transceiver {
    private final Deque<String[]> script = new ArrayDeque<>();
    private int step;

    /** Adds one recorded pair, in the hex form the reader log printed. */
    ScriptedTransceiver expect(String command, String response) {
        script.add(new String[] {command, response});
        return this;
    }

    @Override
    public byte[] transmit(byte[] command) {
        step++;
        String[] next = script.poll();
        assertTrue(next != null, "unexpected extra command " + Hex.encode(command));
        assertEquals(next[0].toUpperCase(), Hex.encode(command), "command " + step);
        return Hex.decode(next[1]);
    }

    void assertDone() {
        assertTrue(script.isEmpty(), script.size() + " recorded commands were never sent");
    }
}
