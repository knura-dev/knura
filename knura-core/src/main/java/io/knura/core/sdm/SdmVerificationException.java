package io.knura.core.sdm;

/** Thrown when a tap cannot be verified. {@link #reason()} says whether it was malformed or forged. */
public final class SdmVerificationException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The tap data has the wrong shape: bad lengths, unexpected PICC data tag, missing fields. */
        MALFORMED,
        /** The data is well formed but the CMAC does not match: wrong key, tampered or counterfeit. */
        BAD_MAC
    }

    private final Reason reason;

    public SdmVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
