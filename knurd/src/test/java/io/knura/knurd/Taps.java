package io.knura.knurd;

import io.knura.core.crypto.Hex;
import io.knura.core.sdm.SdmVerifier;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Builds tap parameters the way a genuine tag would, for tests. */
final class Taps {
    /** NXP's published AN12196 example key. Real keys never appear in this repository. */
    static final byte[] ZERO_KEY = new byte[16];
    static final String AN12196_UID = "04DE5F1EACC040";
    /** AN12196 section 3.4: encrypted PICC data and MAC for UID 04DE5F1EACC040, counter 61. */
    static final Map<String, String> AN12196 = Map.of("e", "EF963FF7828658A599F3041510671E88", "c", "94EED9EE65337086");

    private Taps() {}

    static Map<String, String> plain(String uidHex, int counter, byte[] fileKey) {
        byte[] mac = SdmVerifier.computeMac(Hex.decode(uidHex), counter, new byte[0], fileKey);
        return Map.of("uid", uidHex, "ctr", String.format("%06X", counter), "c", Hex.encode(mac));
    }

    static Map<String, String> encrypted(String uidHex, int counter, byte[] metaKey, byte[] fileKey) throws Exception {
        byte[] picc = new byte[16];
        picc[0] = (byte) 0xC7;
        System.arraycopy(Hex.decode(uidHex), 0, picc, 1, 7);
        picc[8] = (byte) counter;
        picc[9] = (byte) (counter >>> 8);
        picc[10] = (byte) (counter >>> 16);
        Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(metaKey, "AES"), new IvParameterSpec(new byte[16]));
        byte[] mac = SdmVerifier.computeMac(Hex.decode(uidHex), counter, new byte[0], fileKey);
        return Map.of("e", Hex.encode(aes.doFinal(picc)), "c", Hex.encode(mac));
    }

    static String query(Map<String, String> params) {
        StringBuilder q = new StringBuilder();
        params.forEach((k, v) -> q.append(q.isEmpty() ? "" : "&").append(k).append('=').append(v));
        return q.toString();
    }
}
