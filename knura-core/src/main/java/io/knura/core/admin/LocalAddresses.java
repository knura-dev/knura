package io.knura.core.admin;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Which addresses count as "this computer or the local network" for knurd's admin API: loopback,
 * private LAN ranges (10/8, 172.16/12, 192.168/16), link-local, the 100.64/10 range Tailscale
 * gives tailnet devices, and IPv6 unique-local fc00::/7. Everything else is the public internet.
 */
public final class LocalAddresses {
    private LocalAddresses() {}

    public static boolean isLoopback(InetAddress a) {
        return a.isLoopbackAddress();
    }

    public static boolean isLocalNetwork(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        }
        return a instanceof Inet6Address && (b[0] & 0xFE) == 0xFC;
    }

    /** Whether {@code host} names this computer: localhost, 127.x.x.x or ::1, without a lookup. */
    public static boolean isLoopbackName(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return h.equalsIgnoreCase("localhost") || h.matches("127(\\.\\d{1,3}){3}") || h.equals("::1");
    }
}
