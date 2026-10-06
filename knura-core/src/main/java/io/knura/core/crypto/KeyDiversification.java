package io.knura.core.crypto;

/**
 * AES-128 key diversification from NXP AN10922 section 2.2: a card's key is the CMAC of
 * {@code 0x01 || diversification input} under a master key. Unlike plain CMAC, the message is
 * always padded to two blocks, so short inputs give different results than {@link AesCmac#mac}.
 */
public final class KeyDiversification {
    /** Longest diversification input AN10922 allows for AES-128. */
    public static final int MAX_INPUT_LENGTH = 31;
    private static final byte AES128_DIV_CONSTANT = 0x01;

    private KeyDiversification() {}

    /** Returns the 16-byte key diversified from {@code masterKey} with {@code input} (1 to 31 bytes). */
    public static byte[] aes128(byte[] masterKey, byte[] input) {
        if (input.length < 1 || input.length > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("diversification input must be 1 to 31 bytes, got " + input.length);
        }
        byte[] message = new byte[1 + input.length];
        message[0] = AES128_DIV_CONSTANT;
        System.arraycopy(input, 0, message, 1, input.length);
        return AesCmac.mac(masterKey, message, 2);
    }
}
