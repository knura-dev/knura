package io.knura.knurgn;

/** The result of one attempt to deliver a tap event to a connector. */
public record Delivery(Status status, String detail) {
    public enum Status {
        /** The target accepted the event. */
        DELIVERED,
        /** A temporary problem (network error, timeout, busy target): worth trying again. */
        FAILED,
        /** The target said no. Trying again would get the same answer. */
        REFUSED
    }

    public static Delivery delivered(String detail) {
        return new Delivery(Status.DELIVERED, detail);
    }

    public static Delivery failed(String detail) {
        return new Delivery(Status.FAILED, detail);
    }

    public static Delivery refused(String detail) {
        return new Delivery(Status.REFUSED, detail);
    }
}
