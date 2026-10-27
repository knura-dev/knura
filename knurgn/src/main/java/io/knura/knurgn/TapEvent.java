package io.knura.knurgn;

import java.time.Instant;

/**
 * One verified, first-time tap, as knurgN sees it. knurd creates these only after the tap's MAC
 * checked out and its counter was recorded, so every event is genuine and new.
 *
 * @param id {@code UID:counter}, for example {@code 042D6B02936980:61}. A tag never repeats a
 *     counter, so the id never repeats either; receivers use it to drop duplicate deliveries.
 * @param knot the name the owner gave this tag in the knurgN config, or its UID if it has no knot
 *     rule
 * @param name the name the owner gave this tag on a hosted Knura account's dashboard, or null.
 *     Free text, so receivers must treat it as a label and nothing more.
 */
public record TapEvent(String id, String uid, int counter, String knot, Instant tappedAt, String name) {
    static TapEvent of(String uid, int counter, String knot, Instant tappedAt) {
        return of(uid, counter, knot, tappedAt, null);
    }

    static TapEvent of(String uid, int counter, String knot, Instant tappedAt, String name) {
        return new TapEvent(uid + ":" + counter, uid, counter, knot, tappedAt, name);
    }
}
