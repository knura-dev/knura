package io.knura.knurd;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The SDM keys knurd verifies with. One SDMMetaReadKey decrypts every tag's PICC data (it has
 * to, since the UID is unknown until decryption). The SDMFileReadKey can be shared or set per
 * tag; a per-tag key wins over the shared one.
 */
public final class TagKeys {
    private final byte[] metaReadKey;
    private final byte[] sharedFileReadKey;
    private final Map<String, byte[]> fileReadKeys;

    /**
     * @param metaReadKey SDMMetaReadKey, or null if only plain-mirror taps are expected
     * @param sharedFileReadKey SDMFileReadKey for tags without their own, or null
     * @param fileReadKeys per-tag SDMFileReadKeys keyed by uppercase hex UID
     */
    public TagKeys(byte[] metaReadKey, byte[] sharedFileReadKey, Map<String, byte[]> fileReadKeys) {
        this.metaReadKey = metaReadKey == null ? null : checkKey(metaReadKey).clone();
        this.sharedFileReadKey = sharedFileReadKey == null ? null : checkKey(sharedFileReadKey).clone();
        Map<String, byte[]> copies = new HashMap<>();
        fileReadKeys.forEach((uid, key) -> copies.put(uid, checkKey(key).clone()));
        this.fileReadKeys = Map.copyOf(copies);
    }

    public Optional<byte[]> metaReadKey() {
        return Optional.ofNullable(metaReadKey).map(byte[]::clone);
    }

    /** The SDMFileReadKey for this tag, or empty if knurd has no key for it. */
    public Optional<byte[]> fileReadKey(String uidHex) {
        byte[] key = fileReadKeys.getOrDefault(uidHex, sharedFileReadKey);
        return Optional.ofNullable(key).map(byte[]::clone);
    }

    private static byte[] checkKey(byte[] key) {
        if (key.length != 16) {
            throw new IllegalArgumentException("SDM keys must be 16 bytes (32 hex digits)");
        }
        return key;
    }
}
