package io.knura.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class LocalAddressesTest {
    private static boolean local(String ip) throws Exception {
        return LocalAddresses.isLocalNetwork(InetAddress.getByName(ip));
    }

    @Test
    void localNetworks() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "::1", "10.1.2.3", "172.16.0.1", "172.31.255.255",
                "192.168.1.20", "169.254.1.1", "100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1", "fe80::1"}) {
            assertTrue(local(ip), ip);
        }
    }

    @Test
    void publicInternet() throws Exception {
        for (String ip : new String[] {"8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.255.255", "192.0.2.2",
                "2001:4860:4860::8888"}) {
            assertFalse(local(ip), ip);
        }
    }

    @Test
    void loopbackNames() {
        assertTrue(LocalAddresses.isLoopbackName("localhost"));
        assertTrue(LocalAddresses.isLoopbackName("127.0.0.1"));
        assertTrue(LocalAddresses.isLoopbackName("[::1]"));
        assertFalse(LocalAddresses.isLoopbackName("192.168.1.20"));
        assertFalse(LocalAddresses.isLoopbackName("127.0.0.1.evil.example"));
        assertFalse(LocalAddresses.isLoopbackName("localhost.evil.example"));
    }

    @Test
    void fingerprints() {
        String hex = "fa8207b5ead2b764beea15e82d14cdd599bce8d030b5b1b7da8d8b9f704a96f3";
        assertEquals(hex, CertFingerprint.normalize(hex.toUpperCase()));
        assertEquals(hex, CertFingerprint.normalize(hex.replaceAll("(..)(?!$)", "$1:")));
        assertThrows(IllegalArgumentException.class, () -> CertFingerprint.normalize("abc"));
    }
}
