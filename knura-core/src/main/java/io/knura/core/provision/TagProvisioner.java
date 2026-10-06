package io.knura.core.provision;

import io.knura.core.crypto.Hex;
import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.ntag424.Ntag424;
import io.knura.core.ntag424.Ntag424Exception;
import io.knura.core.ntag424.TapUrlLayout;
import io.knura.core.sdm.SdmVerificationException;
import io.knura.core.sdm.SdmVerifier;
import io.knura.core.sdm.VerifiedTap;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sets a tag up for Knura (keys, SDM tap URL) or puts it back to factory state.
 *
 * <p>Provisioning, with the tag's current key 0: write the SDM file settings and the NDEF tap
 * URL, change keys 1 to 4, then key 0 last, so a failure part way leaves key 0 unchanged and the
 * same run can be repeated (keys already changed are recognised and skipped over). It finishes by authenticating with the new key 0 and reading the
 * tag like a phone would, checking the tap URL it produces.
 */
public final class TagProvisioner {
    /** Key version written with Knura keys. Factory keys are version 0. */
    public static final int KNURA_KEY_VERSION = 1;
    /** ChangeKey status when the old key given does not match the key on the tag. */
    private static final int INTEGRITY_ERROR = 0x911E;

    private final Consumer<String> log;

    /** @param log receives one line per step */
    public TagProvisioner(Consumer<String> log) {
        this.log = log;
    }

    /** What provisioning produced: the tag's UID and the tap it gave when read back. */
    public record Result(String uidHex, String tapUrl, VerifiedTap tap) {}

    /**
     * Provisions the tag.
     *
     * @param uid the UID the reader saw; checked against the tag's authenticated UID
     * @param current the keys on the tag now (factory keys for a new tag)
     * @param target the keys to put on the tag
     * @param layout the tap URL to write
     */
    public Result provision(Ntag424 tag, byte[] uid, TagKeySet current, TagKeySet target, TapUrlLayout layout)
            throws IOException {
        authenticateAndCheckUid(tag, uid, current.key(0));

        log.accept("Turning on SDM for the tap URL");
        tag.changeFileSettings(
                Ntag424.NDEF_FILE,
                layout.fileSettings(TagKeyDerivation.SDM_META_READ_KEY, TagKeyDerivation.SDM_FILE_READ_KEY));
        log.accept("Writing tap URL " + layout.template());
        tag.writeData(Ntag424.NDEF_FILE, 0, layout.ndefFile());

        changeKeys(tag, current, target, KNURA_KEY_VERSION);

        log.accept("Checking the new key 0");
        tag.selectApplication();
        tag.authenticate(0, target.key(0));
        tag.endSession();

        log.accept("Reading the tag like a phone would");
        String url = readTapUrl(tag);
        VerifiedTap tap;
        try {
            tap = verifyTapUrl(url, target.key(TagKeyDerivation.SDM_META_READ_KEY), target.key(TagKeyDerivation.SDM_FILE_READ_KEY));
        } catch (SdmVerificationException e) {
            throw new Ntag424Exception("tag wrote a tap URL that does not verify: " + e.getMessage());
        }
        if (!MessageDigest.isEqual(tap.uid(), uid)) {
            throw new Ntag424Exception("tap URL carries UID " + tap.uidHex() + ", expected " + Hex.encode(uid));
        }
        return new Result(Hex.encode(uid), url, tap);
    }

    /**
     * Puts the tag back to factory state: empty NDEF file, SDM off, free read and write, all keys
     * zero.
     */
    public void reset(Ntag424 tag, byte[] uid, TagKeySet current) throws IOException {
        authenticateAndCheckUid(tag, uid, current.key(0));
        log.accept("Clearing the NDEF file");
        tag.writeData(Ntag424.NDEF_FILE, 0, new byte[2]);
        log.accept("Turning SDM off");
        tag.changeFileSettings(Ntag424.NDEF_FILE, TapUrlLayout.FACTORY_FILE_SETTINGS);
        changeKeys(tag, current, TagKeySet.factory(), 0);
        log.accept("Checking the factory key 0");
        tag.selectApplication();
        tag.authenticate(0, Ntag424.FACTORY_KEY);
        tag.endSession();
    }

    /** Reads the tag without authenticating and returns the URL in its NDEF file. */
    public static String readTapUrl(Ntag424 tag) throws IOException {
        byte[] message = tag.readNdefMessage();
        if (message.length == 0) {
            throw new Ntag424Exception("the tag's NDEF file is empty");
        }
        try {
            return TapUrlLayout.decodeUri(message);
        } catch (IllegalArgumentException e) {
            throw new Ntag424Exception("the tag does not hold a URL: " + e.getMessage());
        }
    }

    /** Checks a {@code ?e=...&c=...} tap URL with the tag's SDM keys. */
    public static VerifiedTap verifyTapUrl(String url, byte[] metaReadKey, byte[] fileReadKey)
            throws SdmVerificationException {
        Map<String, String> params = queryParams(url);
        String e = params.get("e");
        String c = params.get("c");
        if (e == null || c == null) {
            throw new SdmVerificationException(SdmVerificationException.Reason.MALFORMED, "URL has no e and c parameters");
        }
        try {
            return SdmVerifier.verifyEncrypted(Hex.decode(e), new byte[0], Hex.decode(c), metaReadKey, fileReadKey);
        } catch (IllegalArgumentException ex) {
            throw new SdmVerificationException(SdmVerificationException.Reason.MALFORMED, "e and c must be hex");
        }
    }

    private void authenticateAndCheckUid(Ntag424 tag, byte[] uid, byte[] key0) throws IOException {
        log.accept("Authenticating with the tag's current key 0");
        tag.selectApplication();
        tag.authenticate(0, key0);
        byte[] real = tag.getCardUid();
        if (!MessageDigest.isEqual(real, uid)) {
            throw new Ntag424Exception(
                    "reader saw UID " + Hex.encode(uid) + " but the tag says " + Hex.encode(real)
                            + "; is a second tag on the reader?");
        }
        log.accept("UID " + Hex.encode(real));
    }

    private void changeKeys(Ntag424 tag, TagKeySet from, TagKeySet to, int version) throws IOException {
        for (int slot = 1; slot < TagKeyDerivation.KEY_SLOTS; slot++) {
            log.accept("Changing key " + slot);
            try {
                tag.changeKey(slot, from.key(slot), to.key(slot), version);
            } catch (Ntag424Exception e) {
                if (e.status() != INTEGRITY_ERROR) {
                    throw e;
                }
                // The old key did not match, most likely because an earlier run that stopped part
                // way already changed this key. Try again assuming it already holds the target.
                log.accept("Key " + slot + " is not what was expected; retrying as already changed");
                tag.selectApplication();
                tag.authenticate(0, from.key(0));
                tag.changeKey(slot, to.key(slot), to.key(slot), version);
            }
        }
        log.accept("Changing key 0");
        tag.changeKey(0, from.key(0), to.key(0), version);
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> params = new HashMap<>();
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.putIfAbsent(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }
}
