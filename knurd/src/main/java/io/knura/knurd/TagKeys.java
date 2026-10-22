package io.knura.knurd;

import io.knura.core.crypto.Hex;
import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.provision.TagKeySet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The SDM keys knurd verifies with, in one of two modes:
 *
 * <ul>
 *   <li><b>Fixed keys</b>: one SDMMetaReadKey decrypts every tag's PICC data (it has to, since the
 *       UID is unknown until decryption), and the SDMFileReadKey is shared or set per tag.
 *   <li><b>Master key</b>: both keys are worked out from one master key with {@link
 *       TagKeyDerivation}, so every tag gets its own SDMFileReadKey without listing tags.
 * </ul>
 *
 * <p>In both modes an explicit per-tag SDMFileReadKey wins.
 */
public final class TagKeys {
    private final byte[] metaReadKey;
    private final byte[] sharedFileReadKey;
    private final byte[] masterKey;
    private final Map<String, byte[]> fileReadKeys;

    /**
     * Fixed keys.
     *
     * @param metaReadKey SDMMetaReadKey, or null if only plain-mirror taps are expected
     * @param sharedFileReadKey SDMFileReadKey for tags without their own, or null
     * @param fileReadKeys per-tag SDMFileReadKeys keyed by uppercase hex UID
     */
    public TagKeys(byte[] metaReadKey, byte[] sharedFileReadKey, Map<String, byte[]> fileReadKeys) {
        this(metaReadKey, sharedFileReadKey, null, fileReadKeys);
    }

    private TagKeys(byte[] metaReadKey, byte[] sharedFileReadKey, byte[] masterKey, Map<String, byte[]> fileReadKeys) {
        this.metaReadKey = metaReadKey == null ? null : checkKey(metaReadKey).clone();
        this.sharedFileReadKey = sharedFileReadKey == null ? null : checkKey(sharedFileReadKey).clone();
        this.masterKey = masterKey == null ? null : checkKey(masterKey).clone();
        Map<String, byte[]> copies = new HashMap<>();
        fileReadKeys.forEach((uid, key) -> copies.put(uid, checkKey(key).clone()));
        this.fileReadKeys = Map.copyOf(copies);
    }

    /**
     * Master-key mode: keys are derived from {@code masterKey}.
     *
     * @param fileReadKeys per-tag SDMFileReadKeys that override derivation, keyed by uppercase hex UID
     */
    public static TagKeys derived(byte[] masterKey, Map<String, byte[]> fileReadKeys) {
        checkKey(masterKey);
        byte[] meta = TagKeyDerivation.fleetKey(masterKey, TagKeyDerivation.SDM_META_READ_KEY);
        return new TagKeys(meta, null, masterKey, fileReadKeys);
    }

    /** "master" when keys are derived from a master key, else "fixed". Never the keys themselves. */
    public String mode() {
        return masterKey == null ? "fixed" : "master";
    }

    public Optional<byte[]> metaReadKey() {
        return Optional.ofNullable(metaReadKey).map(byte[]::clone);
    }

    /** The SDMFileReadKey for this tag, or empty if knurd has no key for it. */
    public Optional<byte[]> fileReadKey(String uidHex) {
        byte[] key = fileReadKeys.get(uidHex);
        if (key == null && masterKey != null) {
            return Optional.of(TagKeyDerivation.tagKey(masterKey, Hex.decode(uidHex), TagKeyDerivation.SDM_FILE_READ_KEY));
        }
        return Optional.ofNullable(key == null ? sharedFileReadKey : key).map(byte[]::clone);
    }

    /**
     * All five keys Knura puts on this tag, for setting it up through a phone, or empty when
     * knurd has no master key. The master key itself never leaves this class.
     */
    public Optional<TagKeySet> knuraKeys(byte[] uid) {
        return masterKey == null ? Optional.empty() : Optional.of(TagKeySet.knura(masterKey, uid));
    }

    private static byte[] checkKey(byte[] key) {
        if (key.length != 16) {
            throw new IllegalArgumentException("SDM keys must be 16 bytes (32 hex digits)");
        }
        return key;
    }
}
