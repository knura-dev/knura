package io.knura.knurgn;

import java.time.Duration;

/**
 * Something a tap can trigger, such as a webhook. A connector gets the event and its own key, and
 * nothing else; {@link Knurgn} decides which events reach it and handles retries and expiry, so a
 * connector cannot skip those checks.
 */
public interface Connector {
    /** The id the owner gave this connector in the config, safe to log. */
    String id();

    /** Short type name, such as {@code webhook}. */
    String type();

    /**
     * Makes one delivery attempt. Must give up within {@code timeout} and must not throw for
     * network problems; return {@link Delivery#failed} instead.
     */
    Delivery deliver(TapEvent event, Duration timeout);
}
