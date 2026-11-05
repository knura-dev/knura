package io.knura.knurgn;

/**
 * Who tapped, when a paired phone signed the tap (see {@code io.knura.core.tap.TapSignature}). A
 * tap from a plain phone browser has no tapper.
 *
 * @param phone the paired phone's name, for example {@code pixel}
 * @param confirmed true when the phone's confirm key signed, which the phone only allows right
 *     after a fingerprint or screen lock
 */
public record Tapper(String phone, boolean confirmed) {
    public Tapper {
        if (phone == null || phone.isEmpty()) {
            throw new IllegalArgumentException("a tapper needs a phone name");
        }
    }
}
