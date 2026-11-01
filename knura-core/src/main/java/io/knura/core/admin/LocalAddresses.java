package io.knura.core.admin;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

    /** Interface names that are containers, VMs or VPNs rather than the home network. */
    private static final List<String> VIRTUAL = List.of("docker", "br-", "veth", "virbr", "vmnet", "vbox", "tailscale",
            "tun", "tap", "utun", "wg", "zt", "cni", "flannel", "podman", "lxc", "lxd");

    /**
     * This computer's IPv4 addresses on the home network (10/8, 172.16/12, 192.168/16), the likely
     * Wi-Fi or Ethernet one first: what a phone on the same Wi-Fi uses to reach it.
     */
    public static List<String> homeNetworkAddresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = nic.getName().toLowerCase(Locale.ROOT);
                if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint() || nic.isVirtual()
                        || VIRTUAL.stream().anyMatch(name::startsWith)) {
                    continue;
                }
                for (InetAddress a : java.util.Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        found.add(a.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            return List.of();
        }
        found.sort(Comparator.comparingInt(LocalAddresses::rank).thenComparing(Comparator.naturalOrder()));
        return found;
    }

    /** Home routers mostly hand out 192.168.x.x, then 10.x.x.x; 172.16/12 is mostly containers. */
    private static int rank(String address) {
        return address.startsWith("192.168.") ? 0 : address.startsWith("10.") ? 1 : 2;
    }
}
