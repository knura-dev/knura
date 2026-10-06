package io.knura.knurd;

/**
 * Remembers the highest read counter accepted for each tag, so a copied tap URL cannot be used
 * twice. Implementations must make {@link #advance} atomic per tag.
 */
public interface CounterStore {
    /**
     * Records {@code counter} for the tag if it is higher than the last one accepted.
     *
     * @param uidHex the tag UID as uppercase hex
     * @return true if the tap is fresh and was recorded, false if it is a replay
     */
    boolean advance(String uidHex, int counter);

    /** The last accepted counter for the tag, or -1 if the tag has never been seen. */
    int last(String uidHex);
}
