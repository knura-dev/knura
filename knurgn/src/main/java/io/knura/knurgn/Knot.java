package io.knura.knurgn;

import java.util.List;

/**
 * A tag's rule: its name and the connectors a verified tap runs. Rules live in knurgN's config on
 * the server, never in the tap URL, so nobody can change what a tap does by editing the link.
 */
public record Knot(String uid, String name, List<Connector> actions) {
    public Knot {
        actions = List.copyOf(actions);
    }
}
