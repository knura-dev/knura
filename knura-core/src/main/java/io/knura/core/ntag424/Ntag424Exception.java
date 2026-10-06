package io.knura.core.ntag424;

import java.io.IOException;

/** A tag command failed: an error status word, a bad response MAC or a malformed response. */
public final class Ntag424Exception extends IOException {
    private static final long serialVersionUID = 1L;

    /** Status word for a failed authentication: wrong key. */
    public static final int AUTHENTICATION_ERROR = 0x91AE;
    /** Status word when the current authentication does not allow the command. */
    public static final int PERMISSION_DENIED = 0x919D;

    /** The status word, or -1 if the failure was not an error status. */
    private final int status;

    public Ntag424Exception(String message) {
        super(message);
        this.status = -1;
    }

    public Ntag424Exception(String command, int status) {
        super(String.format("%s failed with status %04X%s", command, status, describe(status)));
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** True when the tag rejected the key used to authenticate. */
    public boolean isAuthenticationError() {
        return status == AUTHENTICATION_ERROR;
    }

    private static String describe(int status) {
        return switch (status) {
            case 0x911C -> " (illegal command)";
            case 0x911E -> " (integrity error)";
            case 0x9140 -> " (no such key)";
            case 0x917E -> " (length error)";
            case 0x919D -> " (permission denied)";
            case 0x919E -> " (parameter error)";
            case 0x91AD -> " (authentication delay, wait and retry)";
            case 0x91AE -> " (authentication error: wrong key)";
            case 0x91BE -> " (boundary error)";
            case 0x91CA -> " (command aborted)";
            case 0x91EE -> " (memory error)";
            case 0x91F0 -> " (file not found)";
            case 0x6A82 -> " (file or application not found)";
            default -> "";
        };
    }
}
