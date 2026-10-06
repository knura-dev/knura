package io.knura.core.ntag424;

import java.io.IOException;

/**
 * Sends one raw APDU to a tag and returns the raw response, status word included.
 *
 * <p>Implementations must pass the bytes through unchanged. In particular, {@code
 * javax.smartcardio.CommandAPDU} rejects NTAG 424 DNA native commands such as {@code
 * 90 51 00 00 00}, so a PC/SC implementation has to transmit a {@code ByteBuffer} instead.
 */
@FunctionalInterface
public interface Transceiver {
    byte[] transmit(byte[] command) throws IOException;
}
