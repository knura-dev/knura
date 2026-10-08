package io.knura.core.resolver;

import java.util.Optional;

/**
 * Checks that a resolver really sends an owner's taps to the expected server, with a record
 * signed by the owner's own key. knurd runs this now and then so a changed record never goes
 * unnoticed; knurctl runs it on request.
 */
public final class ResolverCheck {
    public enum Status { OK, NOT_REGISTERED, OTHER_KEY, BAD_SIGNATURE, OTHER_SERVER }

    /** Outcome, with the server the resolver sends taps to (null when not registered). */
    public record Result(Status status, String server, String message) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private ResolverCheck() {}

    /**
     * @param fetched the record the resolver served, if any
     * @param owner the owner ID this server was set up with
     * @param publicKeyHex the owner's public key, from this server's settings
     * @param expectedServer this server's public address (public.url)
     */
    public static Result check(Optional<OwnerRecord> fetched, String owner, String publicKeyHex, String expectedServer) {
        if (fetched.isEmpty()) {
            return new Result(Status.NOT_REGISTERED, null,
                    "owner " + owner + " is not registered, so taps through the resolver go nowhere");
        }
        OwnerRecord record = fetched.get();
        if (!owner.equals(record.owner()) || !publicKeyHex.equalsIgnoreCase(record.publicKey())) {
            return new Result(Status.OTHER_KEY, record.server(),
                    "the resolver's record for " + owner + " is not under this owner key");
        }
        if (!record.verify()) {
            return new Result(Status.BAD_SIGNATURE, record.server(),
                    "the resolver's record for " + owner + " is not signed by this owner key");
        }
        if (!record.server().equals(expectedServer)) {
            return new Result(Status.OTHER_SERVER, record.server(),
                    "taps for " + owner + " go to " + record.server() + ", not to " + expectedServer);
        }
        return new Result(Status.OK, record.server(), "taps for " + owner + " go to " + record.server());
    }
}
