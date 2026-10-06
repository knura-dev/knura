package io.knura.knurd;

/** The outcome of one tap. {@code uid} and {@code counter} are set only when the MAC checked out. */
public record TapResult(Status status, String uid, int counter, String detail) {
    public enum Status {
        /** Genuine tag, fresh counter. */
        OK(200),
        /** Missing or badly shaped parameters. */
        MALFORMED(400),
        /** The MAC does not match: forged, tampered, or a tag knurd has no key for. */
        REJECTED(403),
        /** Genuine tag, but the counter is not higher than the last accepted one: a copied URL. */
        REPLAY(409);

        private final int httpStatus;

        Status(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }

        /** Lowercase name used in JSON responses. */
        public String jsonName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    static TapResult failed(Status status, String detail) {
        return new TapResult(status, null, -1, detail);
    }
}
