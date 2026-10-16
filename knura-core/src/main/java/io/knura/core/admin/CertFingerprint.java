package io.knura.core.admin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * SHA-256 fingerprints of TLS certificates, written as 64 lowercase hex digits. knurctl pins the
 * admin API's certificate by fingerprint, so a self-signed certificate is as safe as a bought one.
 */
public final class CertFingerprint {
    private CertFingerprint() {}

    public static String of(X509Certificate cert) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("certificate cannot be encoded", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /**
     * Normalizes a fingerprint as people paste it (any case, with or without colons) to 64
     * lowercase hex digits. Throws {@link IllegalArgumentException} if it isn't one.
     */
    public static String normalize(String value) {
        String hex = value.trim().replace(":", "").toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("a certificate fingerprint is 64 hex digits (SHA-256)");
        }
        return hex;
    }
}
