package io.knura.knurd;

import static io.knura.knurd.Taps.AN12196;
import static io.knura.knurd.Taps.AN12196_UID;
import static io.knura.knurd.Taps.ZERO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.knura.knurd.TapResult.Status;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TapVerifierTest {
    private final MemoryCounterStore counters = new MemoryCounterStore();
    private final TapVerifier verifier = new TapVerifier(new TagKeys(ZERO_KEY, ZERO_KEY, Map.of()), counters);

    @Test
    void acceptsAn12196Tap() {
        TapResult result = verifier.verify(AN12196);
        assertEquals(Status.OK, result.status());
        assertEquals(AN12196_UID, result.uid());
        assertEquals(61, result.counter());
        assertEquals(61, counters.last(AN12196_UID));
    }

    @Test
    void rejectsSameTapTwice() {
        assertEquals(Status.OK, verifier.verify(AN12196).status());
        TapResult again = verifier.verify(AN12196);
        assertEquals(Status.REPLAY, again.status());
        assertEquals(AN12196_UID, again.uid());
    }

    @Test
    void rejectsOlderCounterAndAcceptsNewer() throws Exception {
        assertEquals(Status.OK, verifier.verify(AN12196).status());
        assertEquals(Status.REPLAY, verifier.verify(Taps.encrypted(AN12196_UID, 60, ZERO_KEY, ZERO_KEY)).status());
        assertEquals(Status.OK, verifier.verify(Taps.encrypted(AN12196_UID, 62, ZERO_KEY, ZERO_KEY)).status());
        assertEquals(62, counters.last(AN12196_UID));
    }

    @Test
    void forgedTapDoesNotBurnCounter() {
        Map<String, String> forged = new HashMap<>(Taps.plain(AN12196_UID, 1000, ZERO_KEY));
        forged.put("c", "0000000000000000");
        assertEquals(Status.REJECTED, verifier.verify(forged).status());
        assertEquals(-1, counters.last(AN12196_UID));
        assertEquals(Status.OK, verifier.verify(AN12196).status());
    }

    @Test
    void acceptsPlainMirrorTap() {
        TapResult result = verifier.verify(Taps.plain(AN12196_UID, 0x01023D, ZERO_KEY));
        assertEquals(Status.OK, result.status());
        assertEquals(0x01023D, result.counter());
    }

    @Test
    void usesPerTagKey() {
        byte[] tagKey = new byte[16];
        tagKey[0] = 7;
        TapVerifier perTag =
                new TapVerifier(new TagKeys(ZERO_KEY, null, Map.of(AN12196_UID, tagKey)), new MemoryCounterStore());
        assertEquals(Status.OK, perTag.verify(Taps.plain(AN12196_UID, 5, tagKey)).status());
        assertEquals(Status.REJECTED, perTag.verify(Taps.plain(AN12196_UID, 6, ZERO_KEY)).status());
        assertEquals(Status.REJECTED, perTag.verify(Taps.plain("04000000000001", 1, tagKey)).status());
    }

    @Test
    void reportsMalformedInput() {
        assertEquals(Status.MALFORMED, verifier.verify(Map.of()).status());
        assertEquals(Status.MALFORMED, verifier.verify(Map.of("e", "EF96")).status());
        assertEquals(Status.MALFORMED, verifier.verify(Map.of("e", "zz", "c", "94EED9EE65337086")).status());
        assertEquals(Status.MALFORMED, verifier.verify(Map.of("e", "EF96", "c", "94EED9EE65337086")).status());
        assertEquals(
                Status.MALFORMED,
                verifier.verify(Map.of("uid", AN12196_UID, "ctr", "3D", "c", "94EED9EE65337086")).status());
        assertEquals(
                Status.MALFORMED,
                verifier.verify(Map.of("uid", AN12196_UID, "ctr", "+0003D", "c", "94EED9EE65337086")).status());
    }

    @Test
    void wrongMetaKeyIsNotAccepted() {
        byte[] meta = new byte[16];
        meta[15] = 1;
        TapVerifier wrong = new TapVerifier(new TagKeys(meta, ZERO_KEY, Map.of()), new MemoryCounterStore());
        assertEquals(Status.MALFORMED, wrong.verify(AN12196).status());
    }

    @Test
    void encryptedTapWithoutMetaKeyIsRejected() {
        TapVerifier plainOnly = new TapVerifier(new TagKeys(null, ZERO_KEY, Map.of()), new MemoryCounterStore());
        assertEquals(Status.REJECTED, plainOnly.verify(AN12196).status());
    }
}
