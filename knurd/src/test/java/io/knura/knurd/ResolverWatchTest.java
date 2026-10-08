package io.knura.knurd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.knura.core.resolver.OwnerKey;
import io.knura.core.resolver.OwnerRecord;
import io.knura.core.resolver.ResolverCheck;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResolverWatchTest {
    private static final String OWNER = "zk7hq4m2xa";
    private static final String SERVER = "https://fedora.tail113e80.ts.net";
    private final OwnerKey key = OwnerKey.generate();
    private final KnurdConfig.Resolver resolver =
            new KnurdConfig.Resolver("https://tap.knura.io", OWNER, key.publicKeyHex());

    @Test
    void reportsWhereTapsGo() {
        AtomicReference<OwnerRecord> served = new AtomicReference<>(OwnerRecord.sign(OWNER, SERVER, 1, key));
        ResolverWatch watch = new ResolverWatch(resolver, SERVER, owner -> Optional.ofNullable(served.get()));
        assertEquals(ResolverCheck.Status.OK, watch.checkNow().status());

        served.set(OwnerRecord.sign(OWNER, "https://elsewhere.example.com", 2, key));
        assertEquals(ResolverCheck.Status.OTHER_SERVER, watch.checkNow().status());

        served.set(OwnerRecord.sign(OWNER, "https://evil.example.com", 3, OwnerKey.generate()));
        assertEquals(ResolverCheck.Status.OTHER_KEY, watch.checkNow().status());

        served.set(null);
        assertEquals(ResolverCheck.Status.NOT_REGISTERED, watch.checkNow().status());
    }

    @Test
    void anUnreachableResolverIsNotAnAlarm() {
        ResolverWatch watch = new ResolverWatch(resolver, SERVER, owner -> {
            throw new IOException("could not reach https://tap.knura.io");
        });
        assertNull(watch.checkNow());
    }
}
