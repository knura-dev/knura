package io.knura.core.provision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.crypto.Hex;
import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.ntag424.FakeNtag424;
import io.knura.core.ntag424.Ntag424;
import io.knura.core.ntag424.Ntag424Exception;
import io.knura.core.ntag424.TapUrlLayout;
import io.knura.core.sdm.VerifiedTap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provisioning, re-keying and reset against {@link FakeNtag424}. */
class TagProvisionerTest {
    /** NXP's public AN10922 example master key. Real keys never appear in this repository. */
    private static final byte[] MASTER = Hex.decode("00112233445566778899AABBCCDDEEFF");
    private static final byte[] UID = Hex.decode("04DE5F1EACC040");
    private static final TapUrlLayout LOCAL = TapUrlLayout.forBaseUrl("http://127.0.0.1:8424/tap");

    private final List<String> log = new ArrayList<>();
    private final TagProvisioner provisioner = new TagProvisioner(log::add);

    @Test
    void provisionsFactoryTag() throws Exception {
        FakeNtag424 fake = new FakeNtag424(UID);
        TagKeySet target = TagKeySet.knura(MASTER, UID);

        TagProvisioner.Result result =
                provisioner.provision(new Ntag424(fake), UID, TagKeySet.factory(), target, LOCAL);

        assertEquals("04DE5F1EACC040", result.uidHex());
        assertEquals(1, result.tap().readCounter());
        assertTrue(result.tapUrl().startsWith("http://127.0.0.1:8424/tap?e="), result.tapUrl());
        for (int slot = 0; slot < 5; slot++) {
            assertArrayEquals(target.key(slot), fake.key(slot), "key " + slot);
            assertEquals(1, fake.keyVersion(slot));
        }
        assertArrayEquals(LOCAL.fileSettings(1, 2), fake.fileSettings());
        assertEquals(TagKeyDerivation.fleetKey(MASTER, 1).length, 16);
        assertArrayEquals(TagKeyDerivation.fleetKey(MASTER, 1), fake.key(1), "SDMMetaReadKey is the fleet key");
    }

    @Test
    void everyReadGivesAFreshVerifiableTap() throws Exception {
        FakeNtag424 fake = provisionedTag();
        TagKeySet keys = TagKeySet.knura(MASTER, UID);

        String first = TagProvisioner.readTapUrl(new Ntag424(fake));
        String second = TagProvisioner.readTapUrl(new Ntag424(fake));

        VerifiedTap a = TagProvisioner.verifyTapUrl(first, keys.key(1), keys.key(2));
        VerifiedTap b = TagProvisioner.verifyTapUrl(second, keys.key(1), keys.key(2));
        assertEquals("04DE5F1EACC040", a.uidHex());
        assertEquals(a.readCounter() + 1, b.readCounter());
    }

    @Test
    void reprovisionsKnuraTagWithNewUrl() throws Exception {
        FakeNtag424 fake = provisionedTag();
        TagKeySet keys = TagKeySet.knura(MASTER, UID);
        TapUrlLayout cloud = TapUrlLayout.forBaseUrl("https://knura.io/t");

        TagProvisioner.Result result = provisioner.provision(new Ntag424(fake), UID, keys, keys, cloud);

        assertTrue(result.tapUrl().startsWith("https://knura.io/t?e="), result.tapUrl());
        assertArrayEquals(keys.key(0), fake.key(0));
    }

    @Test
    void rekeysTagFromOtherSoftwaresKeys() throws Exception {
        FakeNtag424 fake = new FakeNtag424(UID);
        TagKeySet old = TagKeySet.of(
                Hex.decode("101112131415161718191A1B1C1D1E1F"),
                Hex.decode("202122232425262728292A2B2C2D2E2F"),
                Hex.decode("303132333435363738393A3B3C3D3E3F"),
                Hex.decode("404142434445464748494A4B4C4D4E4F"),
                Hex.decode("505152535455565758595A5B5C5D5E5F"));
        provisioner.provision(new Ntag424(fake), UID, TagKeySet.factory(), old, LOCAL);

        TagKeySet target = TagKeySet.knura(MASTER, UID);
        provisioner.provision(new Ntag424(fake), UID, old, target, LOCAL);

        for (int slot = 0; slot < 5; slot++) {
            assertArrayEquals(target.key(slot), fake.key(slot), "key " + slot);
        }
    }

    @Test
    void wrongCurrentKeyLeavesTagUntouched() throws Exception {
        FakeNtag424 fake = provisionedTag();
        byte[] settings = fake.fileSettings();

        Ntag424Exception e = assertThrows(
                Ntag424Exception.class,
                () -> provisioner.provision(new Ntag424(fake), UID, TagKeySet.factory(), TagKeySet.knura(MASTER, UID), LOCAL));

        assertTrue(e.isAuthenticationError());
        assertArrayEquals(settings, fake.fileSettings());
        assertArrayEquals(TagKeySet.knura(MASTER, UID).key(0), fake.key(0));
    }

    @Test
    void repeatingAnInterruptedRunFinishesTheJob() throws Exception {
        FakeNtag424 fake = new FakeNtag424(UID);
        fake.failOnChangeKey(3);
        TagKeySet target = TagKeySet.knura(MASTER, UID);

        assertThrows(IOException.class,
                () -> provisioner.provision(new Ntag424(fake), UID, TagKeySet.factory(), target, LOCAL));
        assertArrayEquals(target.key(2), fake.key(2), "keys 1 and 2 changed before the failure");
        assertArrayEquals(new byte[16], fake.key(0), "key 0 is still the factory key");

        provisioner.provision(new Ntag424(fake), UID, TagKeySet.factory(), target, LOCAL);
        for (int slot = 0; slot < 5; slot++) {
            assertArrayEquals(target.key(slot), fake.key(slot), "key " + slot);
        }
    }

    @Test
    void rejectsUidMismatch() {
        FakeNtag424 fake = new FakeNtag424(UID);
        byte[] other = Hex.decode("04000000000001");
        Ntag424Exception e = assertThrows(
                Ntag424Exception.class,
                () -> provisioner.provision(new Ntag424(fake), other, TagKeySet.factory(), TagKeySet.knura(MASTER, other), LOCAL));
        assertTrue(e.getMessage().contains("second tag"), e.getMessage());
    }

    @Test
    void resetReturnsTagToFactoryState() throws Exception {
        FakeNtag424 fake = provisionedTag();

        provisioner.reset(new Ntag424(fake), UID, TagKeySet.knura(MASTER, UID));

        for (int slot = 0; slot < 5; slot++) {
            assertArrayEquals(new byte[16], fake.key(slot), "key " + slot);
            assertEquals(0, fake.keyVersion(slot));
        }
        assertArrayEquals(TapUrlLayout.FACTORY_FILE_SETTINGS, fake.fileSettings());
        assertEquals(0, fake.ndefFile()[0]);
        assertEquals(0, fake.ndefFile()[1]);
        // And it can be provisioned again from factory keys.
        provisioner.provision(new Ntag424(fake), UID, TagKeySet.factory(), TagKeySet.knura(MASTER, UID), LOCAL);
    }

    private FakeNtag424 provisionedTag() throws IOException {
        FakeNtag424 fake = new FakeNtag424(UID);
        provisioner.provision(new Ntag424(fake), UID, TagKeySet.factory(), TagKeySet.knura(MASTER, UID), LOCAL);
        return fake;
    }
}
