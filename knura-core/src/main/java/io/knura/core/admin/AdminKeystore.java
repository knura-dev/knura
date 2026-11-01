package io.knura.core.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * The PKCS#12 keystore holding the admin API's TLS key and certificate, and the file holding its
 * password. Both are secrets: other users may not read them (chmod 600).
 *
 * @param password the keystore password; the caller should not keep it longer than needed
 * @param fingerprint SHA-256 of the certificate, which knurctl pins
 */
public record AdminKeystore(KeyStore keyStore, char[] password, String fingerprint) {
    public static AdminKeystore load(Path keystore, Path passwordFile) throws IOException {
        for (Path file : new Path[] {keystore, passwordFile}) {
            if (!Files.exists(file)) {
                throw new NoSuchFileException(file.toString());
            }
            String problem = PrivateFiles.problem(file);
            if (problem != null) {
                throw new IOException(problem);
            }
        }
        char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip().toCharArray();
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            for (String alias : Collections.list(ks.aliases())) {
                Certificate cert = ks.getCertificate(alias);
                if (ks.isKeyEntry(alias) && cert instanceof X509Certificate x509) {
                    return new AdminKeystore(ks, password, CertFingerprint.of(x509));
                }
            }
            throw new IOException(keystore + " holds no TLS key and certificate");
        } catch (GeneralSecurityException e) {
            throw new IOException("can't open " + keystore + " (wrong password, or not a PKCS#12 keystore?): "
                    + e.getMessage(), e);
        } catch (IOException e) {
            if (e.getCause() instanceof java.security.UnrecoverableKeyException) {
                throw new IOException("wrong password for " + keystore + " in " + passwordFile, e);
            }
            throw e;
        }
    }

    /**
     * Makes a self-signed TLS key and certificate with the JDK's keytool (EC P-256, ten years),
     * plus a random password file, both chmod 600. Neither file may exist yet.
     *
     * @return the new keystore, as {@link #load} reads it
     */
    public static AdminKeystore create(Path keystore, Path passwordFile) throws IOException {
        for (Path p : new Path[] {keystore, passwordFile}) {
            if (Files.exists(p)) {
                throw new IOException(p + " already exists; move it away first");
            }
        }
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        PrivateFiles.create(passwordFile, Base64.getUrlEncoder().withoutPadding().encodeToString(secret) + "\n");

        // keytool writes with the default umask, so build the keystore in a private folder first.
        boolean posix = keystore.getFileSystem().supportedFileAttributeViews().contains("posix");
        Path work = posix
                ? Files.createTempDirectory(keystore.toAbsolutePath().getParent(), ".knurd-tls",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                : Files.createTempDirectory(keystore.toAbsolutePath().getParent(), ".knurd-tls");
        try {
            Path tmp = work.resolve(keystore.getFileName());
            Path keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "keytool.exe" : "keytool");
            Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "knurd-admin",
                    "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3650",
                    "-dname", "CN=knurd admin API", "-storetype", "PKCS12", "-keystore", tmp.toString(),
                    "-storepass:file", passwordFile.toString(), "-keypass:file", passwordFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("keytool failed: " + output.strip());
            }
            if (posix) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            }
            Files.move(tmp, keystore, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(passwordFile);
            throw new IOException("interrupted", e);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(passwordFile);
            throw e;
        } finally {
            try (var leftovers = Files.list(work)) {
                for (Path left : leftovers.toList()) {
                    Files.deleteIfExists(left);
                }
            }
            Files.deleteIfExists(work);
        }
        return load(keystore, passwordFile);
    }
}
