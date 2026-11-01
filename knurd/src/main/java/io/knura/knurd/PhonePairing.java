package io.knura.knurd;

import io.knura.core.admin.AdminKeystore;
import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.LocalAddresses;
import io.knura.core.admin.QrCode;
import io.knura.core.resolver.ResolverClient;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Pairs a phone from the setup page, as {@code knurctl admin pair-phone} does, without a terminal
 * or a restart. It makes sure knurd answers on the home Wi-Fi over TLS (making a certificate and
 * starting the Wi-Fi listener on {@code admin.lan.port} if needed), lets the phone's token set
 * tags up, saves all of that in knurd.properties so it survives a restart, and makes a one-time
 * pairing code for the Knura app to scan.
 */
final class PhonePairing {
    private static final System.Logger LOG = System.getLogger("knurd.admin");

    /** Something the person must fix before a phone can pair; the page shows {@code message}. */
    static final class NotReady extends Exception {
        private static final long serialVersionUID = 1L;
        final transient String code;

        NotReady(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * A started pairing: the link the app scans and its QR code, where the phone will reach knurd,
     * the other addresses it could use, and the firewall command to let it in, if one is needed.
     */
    record Started(String name, String link, boolean[][] qr, String url, String address, List<String> addresses,
            String fingerprint, Instant expires, String firewall) {}

    private final TagKeys keys;
    private final KnurdConfig.Resolver resolver;
    private final KnurdConfig.Admin admin;
    private final TokenStore tokens;
    private final HomeSettings settings;
    private final Clock clock;
    private final java.util.function.Supplier<List<String>> homeAddresses;
    private AdminServer server;
    private String fingerprint;

    /**
     * @param tokens null in plaintext mode, which can't pair phones
     * @param fingerprint the admin certificate's fingerprint, or null when knurd has none yet
     */
    PhonePairing(TagKeys keys, KnurdConfig.Resolver resolver, KnurdConfig.Admin admin, TokenStore tokens,
            HomeSettings settings, String fingerprint, Clock clock) {
        this(keys, resolver, admin, tokens, settings, fingerprint, clock, LocalAddresses::homeNetworkAddresses);
    }

    /** @param homeAddresses this computer's home network addresses, likely one first */
    PhonePairing(TagKeys keys, KnurdConfig.Resolver resolver, KnurdConfig.Admin admin, TokenStore tokens,
            HomeSettings settings, String fingerprint, Clock clock, java.util.function.Supplier<List<String>> homeAddresses) {
        this.homeAddresses = homeAddresses;
        this.keys = keys;
        this.resolver = resolver;
        this.admin = admin;
        this.tokens = tokens;
        this.settings = settings;
        this.fingerprint = fingerprint;
        this.clock = clock;
    }

    void attach(AdminServer server) {
        this.server = server;
    }

    /** The phones that have paired: names in admin.provision.tokens that have a token. */
    List<String> paired() throws IOException {
        if (tokens == null) {
            return List.of();
        }
        Set<String> have = new HashSet<>(tokens.names());
        return settings.phones().stream().filter(have::contains).toList();
    }

    /**
     * Gets knurd ready for a phone named {@code name} and makes its pairing code.
     *
     * @param address the home network address to put in the code, or null for the likely one
     */
    synchronized Started start(String name, String address) throws IOException, NotReady {
        if (!AdminTokens.isValidName(name)) {
            throw new IllegalArgumentException("name: 1-32 of a-z, 0-9, - and _, like pixel-9");
        }
        if (tokens == null) {
            throw new NotReady("plaintext", "This Knura's admin API has no logins (admin.security=plaintext), so a"
                    + " phone can't sign in. Set admin.security=token and admin.tokens.file in knurd.properties,"
                    + " then restart Knura.");
        }
        if (!"master".equals(keys.mode())) {
            throw new NotReady("no_master_key", "Phones set tags up with keys worked out from a master key, and this"
                    + " Knura has none. Use a USB reader instead.");
        }
        String tapBase = tapBase();
        if (tapBase == null) {
            throw new NotReady("no_address", "Give your Knura a web address first (step 3): tags a phone sets up"
                    + " point there.");
        }
        if (tokens.names().contains(name)) {
            throw new NotReady("name_taken", "There is already a phone or computer named " + name + ". Pick another"
                    + " name.");
        }
        List<String> addresses = homeAddresses.get();
        URI url;
        String chosen;
        if (!LocalAddresses.isLoopbackName(admin.host()) && !admin.host().equals("0.0.0.0") && admin.tls()) {
            // knurd already answers the network over TLS, as set up by hand: use that.
            chosen = admin.host();
            url = URI.create("https://" + hostPart(chosen) + ":" + admin.port());
        } else {
            if (addresses.isEmpty()) {
                throw new NotReady("no_network", "This computer isn't on a home network (no 192.168.x.x, 10.x.x.x or"
                        + " 172.16-31.x.x address), so a phone can't reach it. Connect it to your Wi-Fi or router.");
            }
            chosen = address == null ? addresses.get(0) : address;
            if (!addresses.contains(chosen)) {
                throw new IllegalArgumentException("address must be one of " + addresses);
            }
            int lanPort = ensureWifiListener();
            url = URI.create("https://" + chosen + ":" + lanPort);
        }
        settings.addPhone(name);
        NfcRelay nfc = server.nfc();
        if (nfc == null || !nfc.tapBase().equals(tapBase)) {
            Set<String> allowed = new HashSet<>(nfc == null ? Set.of() : nfc.allowed());
            allowed.add(name);
            server.setNfc(new NfcRelay(keys, tapBase, allowed, NfcRelay.REPLY_WAIT));
        } else {
            nfc.allow(name);
        }
        Instant now = clock.instant();
        String code = tokens.startPairing(name, now);
        String link = AdminTokens.pairingLink(url, fingerprint, code);
        return new Started(name, link, QrCode.encode(link), url.toString(), chosen, addresses, fingerprint,
                now.plus(AdminTokens.PAIR_TIME), firewallHint(url.getPort()));
    }

    /** Where tags set up through a phone point: the resolver link, or public.url as saved now. */
    private String tapBase() throws IOException {
        if (resolver != null) {
            return new ResolverClient(resolver.url()).tapBase(resolver.owner());
        }
        String url = settings.publicUrl();
        return url == null ? null : url + "/tap";
    }

    /** Makes the TLS certificate and starts the Wi-Fi listener if they aren't there yet; its port. */
    private int ensureWifiListener() throws IOException {
        if (server.lanPort() > 0) {
            return server.lanPort();
        }
        Path keystore = settings.adminKeystore();
        Path password = settings.adminKeystorePassword();
        AdminKeystore ks;
        if (keystore != null && password != null && Files.exists(keystore)) {
            ks = AdminKeystore.load(keystore, password);
        } else {
            String name = "knurd-admin.p12";
            keystore = settings.folder().resolve(name);
            password = settings.folder().resolve(name + ".password");
            ks = AdminKeystore.create(keystore, password);
            settings.setAdminKeystore(name, name + ".password");
            LOG.log(Level.INFO, "Made a TLS certificate for the Wi-Fi listener: " + keystore);
        }
        fingerprint = ks.fingerprint();
        int port = admin.lanPort() > 0 ? admin.lanPort() : KnurdConfig.DEFAULT_LAN_PORT;
        server.listenOnWifi(port, tlsContext(ks));
        settings.setLanPort(port);
        LOG.log(Level.INFO, "Admin API now also on https://0.0.0.0:" + port + " for phones on the home network");
        return port;
    }

    /** An SSLContext serving the keystore's key; clears the password after. */
    static SSLContext tlsContext(AdminKeystore keystore) throws IOException {
        try {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keystore.keyStore(), keystore.password());
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(kmf.getKeyManagers(), null, null);
            return tls;
        } catch (GeneralSecurityException e) {
            throw new IOException("can't use the TLS key: " + e.getMessage(), e);
        } finally {
            Arrays.fill(keystore.password(), '\0');
        }
    }

    /** The command that lets the port through this computer's firewall, if it has one we know. */
    static String firewallHint(int port) {
        String os = System.getProperty("os.name", "");
        if (!os.startsWith("Linux")) {
            return null;
        }
        if (onPath("firewall-cmd")) {
            return "sudo firewall-cmd --add-port=" + port + "/tcp --permanent && sudo firewall-cmd --reload";
        }
        if (onPath("ufw")) {
            return "sudo ufw allow " + port + "/tcp";
        }
        return null;
    }

    private static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return Files.isExecutable(Path.of("/usr/sbin", command)) || Files.isExecutable(Path.of("/sbin", command));
    }

    private static String hostPart(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
