package io.knura.core.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;

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
}
