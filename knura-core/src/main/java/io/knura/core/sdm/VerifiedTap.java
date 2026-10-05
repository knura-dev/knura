package io.knura.core.sdm;

import io.knura.core.crypto.Hex;
import java.util.Arrays;

/**
 * A tap whose SDM CMAC checked out: the tag's UID and the read counter it reported.
 *
 * <p>A valid MAC proves the URL came from a tag holding the right key. It does not prove the
 * tap is fresh: callers must still reject counters at or below the last one they accepted.
 */
public record VerifiedTap(byte[] uid, int readCounter) {
    public VerifiedTap {
        uid = uid.clone();
    }

    @Override
    public byte[] uid() {
        return uid.clone();
    }

    /** The UID as uppercase hex, e.g. {@code 04DE5F1EACC040}. */
    public String uidHex() {
        return Hex.encode(uid);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VerifiedTap other
                && readCounter == other.readCounter
                && Arrays.equals(uid, other.uid);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(uid) + readCounter;
    }

    @Override
    public String toString() {
        return "VerifiedTap[uid=" + uidHex() + ", readCounter=" + readCounter + "]";
    }
}
