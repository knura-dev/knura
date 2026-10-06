package io.knura.core.provision;

import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.ntag424.Ntag424;
import java.util.Arrays;

/** The five AES keys of one NTAG 424 DNA tag, slot 0 (application master key) to slot 4. */
public final class TagKeySet {
    private final byte[][] keys;

    private TagKeySet(byte[][] keys) {
        if (keys.length != TagKeyDerivation.KEY_SLOTS) {
            throw new IllegalArgumentException("a tag has 5 keys, got " + keys.length);
        }
        this.keys = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null || keys[i].length != 16) {
                throw new IllegalArgumentException("key " + i + " must be 16 bytes");
            }
            this.keys[i] = keys[i].clone();
        }
    }

    /** Explicit keys for slots 0 to 4, e.g. ones a tag was set up with by other software. */
    public static TagKeySet of(byte[]... keys) {
        return new TagKeySet(keys);
    }

    /** All-zero keys, as a tag leaves the factory. */
    public static TagKeySet factory() {
        byte[][] keys = new byte[TagKeyDerivation.KEY_SLOTS][];
        Arrays.setAll(keys, i -> Ntag424.FACTORY_KEY.clone());
        return new TagKeySet(keys);
    }

    /**
     * Knura's keys for the tag with this UID under {@code masterKey}: the shared SDMMetaReadKey
     * in slot 1, per-tag keys in every other slot (see {@link TagKeyDerivation}).
     */
    public static TagKeySet knura(byte[] masterKey, byte[] uid) {
        byte[][] keys = new byte[TagKeyDerivation.KEY_SLOTS][];
        for (int slot = 0; slot < keys.length; slot++) {
            keys[slot] = slot == TagKeyDerivation.SDM_META_READ_KEY
                    ? TagKeyDerivation.fleetKey(masterKey, slot)
                    : TagKeyDerivation.tagKey(masterKey, uid, slot);
        }
        return new TagKeySet(keys);
    }

    public byte[] key(int slot) {
        return keys[slot].clone();
    }

    /** Overwrites the keys in memory. */
    public void destroy() {
        for (byte[] key : keys) {
            Arrays.fill(key, (byte) 0);
        }
    }
}
