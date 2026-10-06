package io.knura.knurgn;

import java.util.Arrays;

/**
 * A connector's key. {@link #toString()} never shows the value, so a secret that ends up in a log
 * line or an exception message stays hidden.
 */
public final class Secret {
    private final String connectorId;
    private final byte[] value;

    public Secret(String connectorId, byte[] value) {
        this.connectorId = connectorId;
        this.value = value.clone();
    }

    public String connectorId() {
        return connectorId;
    }

    byte[] value() {
        return value.clone();
    }

    @Override
    public String toString() {
        return "Secret[" + connectorId + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Secret s && connectorId.equals(s.connectorId) && Arrays.equals(value, s.value);
    }

    @Override
    public int hashCode() {
        return connectorId.hashCode();
    }
}
