package io.knura.core.ntag424;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
import io.knura.core.sdm.SdmVerifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Replays APDU traces recorded from a real NTAG 424 DNA tag on an ACR122U reader (the earlier
 * Latch provisioning tool, August 2026). Each test feeds this implementation the RndA the
 * recorded run used, so every command it sends must match the recorded bytes exactly, and the
 * tag's recorded responses must pass its MAC and decryption checks.
 *
 * <p>The sessions authenticate with the all-zero factory key, so everything in them, including
 * the keys the run wrote, can be decrypted from the trace alone. The keys below belong to one
 * development tag (UID 041C6B02936980) and protect nothing else.
 */
class Ntag424TraceTest {
    private static final String SELECT = "00a4000c02e11000";
    private static final String UID = "041C6B02936980";

    /** Keys the recorded provisioning run wrote to slots 0 to 4, recovered from its ChangeKey data. */
    private static final List<byte[]> LATCH_KEYS = List.of(
            Hex.decode("7b69061c47e4b640ad0de09f954e417f"),
            Hex.decode("96db6aaab4d7dff050d8e585c3907bcd"),
            Hex.decode("f5f32b888e5ddc6c62394dcd28e57289"),
            Hex.decode("9213b1bd912f06cae3a322430219fccb"),
            Hex.decode("7cf5e06b59950b6e0db976456ec33b49"));

    @Test
    void readsUidAfterFactoryKeyAuthentication() throws Exception {
        // Reader log: hardware-traces.md, message 66.
        ScriptedTransceiver tag = new ScriptedTransceiver()
                .expect(SELECT, "9000")
                .expect("9071000002000000", "513f523caae82d0bb769718f03159cb991af")
                .expect("90af00002037f8488ec2caad6f2f68953727222f67cd90fac85a4b588a0862238bf2b6102000",
                        "72077e3dd3b467b25f27eb6aec56f5750092bec2da611ab9da4eb6c9feb600519100")
                .expect("905100000890a91dafa3498b0800", "bb7fc2b0db0b0bc04dd18a4c8eda193a91f473475aa6fbac9100");
        Ntag424 ntag = new Ntag424(tag, rndA("7b23d76e1643841617ef3ba92faa966f"));

        ntag.selectApplication();
        ntag.authenticate(0, Ntag424.FACTORY_KEY);
        assertEquals(UID, Hex.encode(ntag.getCardUid()));
        tag.assertDone();
    }

    @Test
    void replaysRecordedProvisioningRun() throws Exception {
        // Reader log: hardware-traces.md, message 68. Plain URL mirror, template
        // http://localhost:8080/t/{UID}?c={COUNTER}&mac={MAC}.
        ScriptedTransceiver tag = new ScriptedTransceiver()
                .expect(SELECT, "9000")
                .expect("9071000002000000", "e63c056934341a491d45b9cea74da1bf91af")
                .expect("90af000020b49280fb2fa52ac8114e2e0cd2125301f6200a239aa002d99a8d44d1eb8d2b5f00",
                        "5f921681a8ff2850fdd03e2d537a2783a50313030269c86d5bbb1ffbd9628be49100")
                .expect("9051000008c43fb6a1aa89131900", "78143cfead9bd893a6845c4a2879c8d872c3f698e94798979100")
                .expect("90f50000090289ec9544aefe7d4700", "0000e0ee000100d196b4c665e2b48d9100")
                .expect("908d000052020000004b00000049d101455500687474703a2f2f6c6f63616c686f73743a383038302f742f"
                                + "2a2a2a2a2a2a2a2a2a2a2a2a2a2a3f633d2a2a2a2a2a2a266d61633d2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a00",
                        "9100")
                .expect("90f5000009029ae2760ce52ecdf600", "0000e0ee000100ceccaf3317c5db4e9100")
                .expect("905f0000290202bdeffd1498c4d76e9a714bf542340bef828be5a4b25d6d767072d7ef2abe10861887002b9fef9700",
                        "4348a979ca1eb3c29100")
                .expect("90c400002901c1b553706709d10865f2d7c6c63d7e2707d1c97d33fca66c64b3eae2f5d0457037e1a99bf851ed2800",
                        "c3bc80b1a74bb9ab9100")
                .expect("90c40000290246f80b515c5bfbf33667c026a01fb114b98d1638d01d1fab6d6b44e7f0c399b456a75be6ca29dc4a00",
                        "4e906a66253a82c59100")
                .expect("90c4000029036dffb37faf24148466427f0ec4c49356832c7ab9e0e42b916b24ec3b04c37a7645710e324e19e6ff00",
                        "bce1853da2efa6629100")
                .expect("90c4000029045fc1569422b4399e7aa261544ef983a8a5fa4620dd3a0645e4033015c67d461759082af825e9174900",
                        "b1aee1c7223f99489100")
                .expect("90c4000029000bafb2313a2aa321b071323234d476c442b783a0f6c434e1a72dd7b92c67f70f7c547317297ae10a00",
                        "9100")
                // The recorded run then tried the factory key again, which the tag now refuses.
                .expect("9071000002000000", "91ec9c7095907295c48128ff16fd83bf91af")
                .expect("90af0000209a2e75237b02e5010932b6f6a7455cf683042e058c53b206d976c2277d530c4e00", "91ae");
        Ntag424 ntag = new Ntag424(tag, rndA("431e45af1318095945d815299ac5d6a9", "f858443ee553fa07120fbe7ef7c4ffbc"));

        ntag.selectApplication();
        ntag.authenticate(0, Ntag424.FACTORY_KEY);
        assertEquals(UID, Hex.encode(ntag.getCardUid()));
        assertEquals("0000E0EE000100", Hex.encode(ntag.getFileSettings(Ntag424.NDEF_FILE)));
        ntag.writeData(Ntag424.NDEF_FILE, 0, Hex.decode(
                "0049d101455500687474703a2f2f6c6f63616c686f73743a383038302f742f2a2a2a2a2a2a2a2a2a2a2a2a2a2a"
                        + "3f633d2a2a2a2a2a2a266d61633d2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a"));
        assertEquals("0000E0EE000100", Hex.encode(ntag.getFileSettings(Ntag424.NDEF_FILE)));
        // SDM on, plain UID (offset 0x1F) and counter (0x30) mirrors, MAC at 0x3B under key 2.
        ntag.changeFileSettings(Ntag424.NDEF_FILE, Hex.decode("40e0eec1ffe21f00003000003b00003b0000"));
        for (int slot = 1; slot <= 4; slot++) {
            ntag.changeKey(slot, Ntag424.FACTORY_KEY, LATCH_KEYS.get(slot), 1);
        }
        ntag.changeKey(0, Ntag424.FACTORY_KEY, LATCH_KEYS.get(0), 1);
        assertFalse(ntag.isAuthenticated(), "changing the authentication key ends the session");

        Ntag424Exception refused =
                assertThrows(Ntag424Exception.class, () -> ntag.authenticate(0, Ntag424.FACTORY_KEY));
        assertTrue(refused.isAuthenticationError());
        tag.assertDone();
    }

    @Test
    void recoveredFileReadKeyVerifiesRecordedTaps() {
        // Phone taps after that run (hardware-traces.md, message 74): counters 1 and 2.
        byte[] uid = Hex.decode(UID);
        byte[] key2 = LATCH_KEYS.get(2);
        assertArrayEquals(Hex.decode("8D6EBE5C32C9B155"), SdmVerifier.computeMac(uid, 1, new byte[0], key2));
        assertArrayEquals(Hex.decode("F6E4702CCB20B188"), SdmVerifier.computeMac(uid, 2, new byte[0], key2));
    }

    @Test
    void rejectsTamperedResponseMac() throws Exception {
        ScriptedTransceiver tag = new ScriptedTransceiver()
                .expect(SELECT, "9000")
                .expect("9071000002000000", "513f523caae82d0bb769718f03159cb991af")
                .expect("90af00002037f8488ec2caad6f2f68953727222f67cd90fac85a4b588a0862238bf2b6102000",
                        "72077e3dd3b467b25f27eb6aec56f5750092bec2da611ab9da4eb6c9feb600519100")
                // Last MAC byte flipped.
                .expect("905100000890a91dafa3498b0800", "bb7fc2b0db0b0bc04dd18a4c8eda193a91f473475aa6fbad9100");
        Ntag424 ntag = new Ntag424(tag, rndA("7b23d76e1643841617ef3ba92faa966f"));
        ntag.selectApplication();
        ntag.authenticate(0, Ntag424.FACTORY_KEY);
        Ntag424Exception e = assertThrows(Ntag424Exception.class, ntag::getCardUid);
        assertTrue(e.getMessage().contains("MAC"));
        assertFalse(ntag.isAuthenticated());
    }

    @Test
    void reportsUnauthenticatedGetCardUidStatus() {
        // hardware-traces.md, message 62: GetCardUid without a session answers 917E. This
        // implementation refuses to send it at all.
        Ntag424 ntag = new Ntag424(new ScriptedTransceiver());
        assertThrows(IllegalStateException.class, ntag::getCardUid);
    }

    private static java.util.function.Supplier<byte[]> rndA(String... values) {
        Deque<byte[]> queue = new ArrayDeque<>();
        for (String v : values) {
            queue.add(Hex.decode(v));
        }
        return queue::remove;
    }
}
