package io.knura.core.resolver;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * An owner's Ed25519 key for the tap resolver. Only the owner holds the private half; the resolver
 * stores the public half with the owner's record and accepts changes to it only when signed by
 * the private half. Keys are written as lowercase hex: 32 bytes of private seed, 32 bytes of
 * public key.
 */
public final class OwnerKey {
    /** DER prefix of an X.509 SubjectPublicKeyInfo for a raw Ed25519 key (RFC 8410). */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final int KEY_BYTES = 32;

    private final PrivateKey privateKey;
    private final byte[] seed;
    private final byte[] publicKey;

    private OwnerKey(PrivateKey privateKey, byte[] seed, byte[] publicKey) {
        this.privateKey = privateKey;
        this.seed = seed;
        this.publicKey = publicKey;
    }

    /** A new random key. */
    public static OwnerKey generate() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] spki = pair.getPublic().getEncoded();
            byte[] raw = Arrays.copyOfRange(spki, spki.length - KEY_BYTES, spki.length);
            // PKCS#8 for Ed25519 ends with the 32-byte seed (RFC 8410, no public key attribute).
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - KEY_BYTES, pkcs8.length);
            return load(HexFormat.of().formatHex(seed), HexFormat.of().formatHex(raw));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no Ed25519", e);
        }
    }

    /**
     * Loads a key from its hex halves, as stored in knurd.properties. Throws
     * {@link IllegalArgumentException} if they are malformed or don't belong together.
     */
    public static OwnerKey load(String seedHex, String publicKeyHex) {
        byte[] seed = rawKey(seedHex, "resolver.key");
        byte[] publicKey = rawKey(publicKeyHex, "resolver.publicKey");
        PrivateKey privateKey;
        try {
            privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no Ed25519", e);
        }
        OwnerKey key = new OwnerKey(privateKey, seed, publicKey);
        byte[] probe = "knura-resolver-key-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (!verify(publicKey, probe, key.sign(probe))) {
            throw new IllegalArgumentException("resolver.key and resolver.publicKey are not a pair");
        }
        return key;
    }

    public String seedHex() {
        return HexFormat.of().formatHex(seed);
    }

    public String publicKeyHex() {
        return HexFormat.of().formatHex(publicKey);
    }

    /** True if this key's public half is {@code publicKeyHex}, compared in constant time. */
    public boolean hasPublicKey(String publicKeyHex) {
        try {
            return MessageDigest.isEqual(publicKey, HexFormat.of().parseHex(publicKeyHex));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public byte[] sign(byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** True if {@code signature} is {@code publicKey}'s (raw, 32 bytes) signature over {@code message}. */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey.length != KEY_BYTES) {
            return false;
        }
        byte[] spki = new byte[SPKI_PREFIX.length + KEY_BYTES];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(publicKey, 0, spki, SPKI_PREFIX.length, KEY_BYTES);
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            // Not a valid point, or a malformed signature.
            return false;
        }
    }

    private static byte[] rawKey(String hex, String name) {
        try {
            byte[] key = HexFormat.of().parseHex(hex.trim());
            if (key.length == KEY_BYTES) {
                return key;
            }
        } catch (IllegalArgumentException e) {
            // Reported below.
        }
        throw new IllegalArgumentException(name + " must be 64 hex digits");
    }
}
