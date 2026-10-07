package io.knura.knurgn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class MatterConnectorTest {
    @Test
    void allowsOnlyThisComputer() {
        for (String ok : new String[] {"http://127.0.0.1:5541/tap", "http://localhost:5541/tap", "http://[::1]:5541/tap"}) {
            assertDoesNotThrow(() -> MatterConnector.checkUrl("home", URI.create(ok)), ok);
        }
        // Even the home network is refused: the bridge only listens on this computer, so anything
        // else would be some other program receiving signed taps.
        for (String bad : new String[] {
                "http://192.168.1.20:5541/tap", "https://example.com/tap", "http://127.0.0.2:5541/tap",
                "ftp://127.0.0.1/tap", "/tap"}) {
            assertThrows(IllegalArgumentException.class, () -> MatterConnector.checkUrl("home", URI.create(bad)), bad);
        }
    }
}
