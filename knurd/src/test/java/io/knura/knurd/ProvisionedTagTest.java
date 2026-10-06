package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
import io.knura.core.ntag424.FakeNtag424;
import io.knura.core.ntag424.Ntag424;
import io.knura.core.ntag424.TapUrlLayout;
import io.knura.core.provision.TagKeySet;
import io.knura.core.provision.TagProvisioner;
import io.knura.knurd.TapResult.Status;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A tag set up by knurctl's provisioning code verifies in knurd with only the master key. */
class ProvisionedTagTest {
    /** NXP's public AN10922 example master key. Real keys never appear in this repository. */
    private static final byte[] MASTER = Hex.decode("00112233445566778899AABBCCDDEEFF");
    private static final byte[] UID = Hex.decode("04DE5F1EACC040");

    @Test
    void provisionedTagTapsVerifyAndReplaysAreRefused() throws Exception {
        FakeNtag424 tag = new FakeNtag424(UID);
        new TagProvisioner(line -> {})
                .provision(new Ntag424(tag), UID, TagKeySet.factory(), TagKeySet.knura(MASTER, UID),
                        TapUrlLayout.forBaseUrl("http://127.0.0.1:8424/tap"));
        TapVerifier knurd = new TapVerifier(TagKeys.derived(MASTER, Map.of()), new MemoryCounterStore());

        Map<String, String> first = query(TagProvisioner.readTapUrl(new Ntag424(tag)));
        Map<String, String> second = query(TagProvisioner.readTapUrl(new Ntag424(tag)));

        TapResult ok = knurd.verify(first);
        assertEquals(Status.OK, ok.status());
        assertEquals("04DE5F1EACC040", ok.uid());
        assertEquals(Status.REPLAY, knurd.verify(first).status());
        assertEquals(Status.OK, knurd.verify(second).status());
    }

    @Test
    void otherMasterKeyIsRejected() throws Exception {
        FakeNtag424 tag = new FakeNtag424(UID);
        new TagProvisioner(line -> {})
                .provision(new Ntag424(tag), UID, TagKeySet.factory(), TagKeySet.knura(MASTER, UID),
                        TapUrlLayout.forBaseUrl("http://127.0.0.1:8424/tap"));
        byte[] otherMaster = Hex.decode("FFEEDDCCBBAA99887766554433221100");
        TapVerifier knurd = new TapVerifier(TagKeys.derived(otherMaster, Map.of()), new MemoryCounterStore());

        // Usually MALFORMED (the PICC data decrypts to garbage); now and then the garbage looks
        // well formed and the MAC check refuses it instead.
        Status status = knurd.verify(query(TagProvisioner.readTapUrl(new Ntag424(tag)))).status();
        assertTrue(status == Status.MALFORMED || status == Status.REJECTED, status.toString());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], kv[1]);
        }
        return params;
    }
}
