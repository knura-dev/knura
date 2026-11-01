package io.knura.knurd;

import io.knura.core.admin.AdminKeystore;
import io.knura.core.admin.AdminTokens;
import io.knura.core.admin.PrivateFiles;
import io.knura.core.crypto.Hex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Map;

/**
 * {@code knurd --home <folder> [--lan]}: how the installers start knurd. On the first start it makes
 * the settings folder (owner-only) and a starter knurd.properties with a new master key and the
 * admin API on, so the setup page at http://127.0.0.1:8425/setup works straight away. With {@code
 * --lan}, for a computer with no screen such as a Raspberry Pi, it also turns on the Wi-Fi listener
 * (TLS, {@code admin.lan.port}) and makes a {@link SetupCode}, so the first password can be set from
 * another computer. Later starts leave the folder alone.
 */
final class FirstRun {
    static final String TOKENS_FILE = "knurd-admin-tokens.properties";

    private FirstRun() {}

    /** Makes the folder and its settings if they aren't there; the settings file. */
    static Path prepare(Path dir, boolean lan) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        }
        Path config = dir.resolve("knurd.properties");
        if (Files.exists(config)) {
            return config;
        }
        byte[] master = new byte[16];
        new SecureRandom().nextBytes(master);
        StringBuilder text = new StringBuilder("""
                # Settings for knurd and knurctl, made by Knura's installer. Keep this file secret:
                # master.key unlocks every tag set up with it. The setup page (http://127.0.0.1:8425/setup)
                # changes this file; you may edit it too, then restart Knura.
                listen.host=127.0.0.1
                listen.port=8424
                master.key=%s
                counters.file=knurd-counters.properties
                # Knura's log, kept small: at 10 MB it moves to .1 and 4 old files are kept.
                log.file=logs/knurd.log
                # The admin API and setup page: this computer only, over plain HTTP.
                admin.tokens.file=%s
                """.formatted(Hex.encode(master), TOKENS_FILE));
        java.util.Arrays.fill(master, (byte) 0);
        Path tokens = dir.resolve(TOKENS_FILE);
        if (!Files.exists(tokens)) {
            PrivateFiles.replace(tokens, AdminTokens.format(Map.of()));
        }
        if (lan) {
            String keystore = "knurd-admin.p12";
            if (!Files.exists(dir.resolve(keystore))) {
                AdminKeystore.create(dir.resolve(keystore), dir.resolve(keystore + ".password"));
            }
            text.append("""
                    # Phones and other computers on your home network reach the setup page over TLS here.
                    admin.tls.keystore=%s
                    admin.tls.password.file=%s
                    admin.lan.port=%d
                    """.formatted(keystore, keystore + ".password", KnurdConfig.DEFAULT_LAN_PORT));
        }
        PrivateFiles.create(config, text.toString());
        return config;
    }
}
