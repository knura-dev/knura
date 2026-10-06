package io.knura.knurd;

import io.knura.core.crypto.Hex;
import io.knura.core.sdm.SdmVerificationException;
import io.knura.core.sdm.SdmVerificationException.Reason;
import io.knura.core.sdm.SdmVerifier;
import io.knura.core.sdm.VerifiedTap;
import io.knura.knurd.TapResult.Status;
import java.util.Map;

/**
 * Checks a tap's SDM MAC with knura-core, then rejects replays through the {@link CounterStore}.
 * The counter is only recorded after the MAC checks out, so forged taps cannot burn counters.
 *
 * <p>Query parameters follow NXP AN12196:
 *
 * <ul>
 *   <li>Encrypted mirror: {@code e} = 32 hex digits of PICC data, {@code c} = 16 hex digits of MAC.
 *   <li>Plain mirror: {@code uid} = 14 hex digits, {@code ctr} = 6 hex digits (most significant
 *       first, as the tag prints it), {@code c} = MAC.
 * </ul>
 *
 * <p>Only an empty MAC input is supported for now (SDMMACInputOffset equal to SDMMACOffset).
 */
public final class TapVerifier {
    private static final byte[] EMPTY = new byte[0];

    private final TagKeys keys;
    private final CounterStore counters;

    public TapVerifier(TagKeys keys, CounterStore counters) {
        this.keys = keys;
        this.counters = counters;
    }

    public TapResult verify(Map<String, String> params) {
        String mac = params.get("c");
        if (mac == null) {
            return TapResult.failed(Status.MALFORMED, "missing MAC parameter c");
        }
        VerifiedTap tap;
        try {
            if (params.containsKey("e")) {
                tap = verifyEncrypted(params.get("e"), mac);
            } else if (params.containsKey("uid") && params.containsKey("ctr")) {
                tap = verifyPlain(params.get("uid"), params.get("ctr"), mac);
            } else {
                return TapResult.failed(Status.MALFORMED, "expected e and c, or uid, ctr and c");
            }
        } catch (IllegalArgumentException e) {
            return TapResult.failed(Status.MALFORMED, "parameters are not valid hex");
        } catch (UnknownTagException e) {
            return TapResult.failed(Status.REJECTED, "unknown tag");
        } catch (SdmVerificationException e) {
            return switch (e.reason()) {
                case MALFORMED -> TapResult.failed(Status.MALFORMED, e.getMessage());
                case BAD_MAC -> TapResult.failed(Status.REJECTED, "MAC does not match");
            };
        }
        String uid = tap.uidHex();
        if (!counters.advance(uid, tap.readCounter())) {
            return new TapResult(Status.REPLAY, uid, tap.readCounter(), "counter already used");
        }
        return new TapResult(Status.OK, uid, tap.readCounter(), null);
    }

    private VerifiedTap verifyEncrypted(String piccHex, String macHex)
            throws SdmVerificationException, UnknownTagException {
        byte[] meta = keys.metaReadKey().orElseThrow(UnknownTagException::new);
        byte[] picc = Hex.decode(piccHex);
        byte[] mac = Hex.decode(macHex);
        // Decrypt first to learn the UID, which picks the file key; the MAC check below is what
        // makes the UID and counter trustworthy.
        VerifiedTap unchecked = SdmVerifier.decryptPiccData(picc, meta);
        return SdmVerifier.verifyPlain(
                unchecked.uid(), unchecked.readCounter(), EMPTY, mac, fileKey(unchecked.uidHex()));
    }

    private VerifiedTap verifyPlain(String uidHex, String ctrHex, String macHex)
            throws SdmVerificationException, UnknownTagException {
        byte[] uid = Hex.decode(uidHex);
        if (uid.length != SdmVerifier.UID_LENGTH) {
            throw new SdmVerificationException(Reason.MALFORMED, "uid must be 14 hex digits");
        }
        byte[] ctr = Hex.decode(ctrHex);
        if (ctr.length != 3) {
            throw new SdmVerificationException(Reason.MALFORMED, "ctr must be 6 hex digits");
        }
        int counter = (ctr[0] & 0xFF) << 16 | (ctr[1] & 0xFF) << 8 | (ctr[2] & 0xFF);
        byte[] mac = Hex.decode(macHex);
        return SdmVerifier.verifyPlain(uid, counter, EMPTY, mac, fileKey(Hex.encode(uid)));
    }

    private byte[] fileKey(String uidHex) throws UnknownTagException {
        return keys.fileReadKey(uidHex).orElseThrow(UnknownTagException::new);
    }

    private static final class UnknownTagException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
