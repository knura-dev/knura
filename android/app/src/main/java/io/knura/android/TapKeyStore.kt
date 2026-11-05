package io.knura.android

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's two tap keys (see [SignedTap]), EC P-256 key pairs in the Android Keystore whose
 * private halves never leave the phone's secure hardware.
 *
 * - The **everyday** key only works while the phone is unlocked, so a tap is just "unlock, tap".
 * - The **confirm** key needs a fingerprint or the screen lock for every single signature. The
 *   server can tell which key signed, so it knows a confirmed tap really was confirmed.
 *
 * Pairing again makes new keys, so a server never keeps trusting keys from an old pairing.
 */
class TapKeyStore(private val packageManager: PackageManager) {
    private val keyStore: KeyStore get() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun has(): Boolean = keyStore.containsAlias(EVERYDAY)

    /** Makes both keys, replacing any old ones. */
    fun create() {
        delete()
        make(EVERYDAY, confirm = false)
        make(CONFIRM, confirm = true)
    }

    fun delete() {
        val ks = keyStore
        for (alias in listOf(EVERYDAY, CONFIRM)) {
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    /** A public key as the server reads it: base64 of its X.509 encoding. */
    fun publicKey(kind: String): String? {
        val cert = keyStore.getCertificate(alias(kind)) ?: return null
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    /** Signs with the everyday key; fails if the phone is locked. */
    fun signEveryday(message: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(keyStore.getKey(EVERYDAY, null) as PrivateKey)
        s.update(message)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    /** A signer for the confirm key, to hand to the fingerprint prompt as its crypto object. */
    fun confirmSigner(): Signature = Signature.getInstance("SHA256withECDSA").apply {
        initSign(keyStore.getKey(CONFIRM, null) as PrivateKey)
    }

    private fun make(alias: String, confirm: Boolean) {
        val strongBox = packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            generate(alias, confirm, strongBox)
        } catch (e: StrongBoxUnavailableException) {
            generate(alias, confirm, false)
        }
    }

    private fun generate(alias: String, confirm: Boolean, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
        if (confirm) {
            spec.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(true)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(spec.build())
            generateKeyPair()
        }
    }

    private fun alias(kind: String) = if (kind == SignedTap.CONFIRM) CONFIRM else EVERYDAY

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val EVERYDAY = "tap_everyday"
        const val CONFIRM = "tap_confirm"
    }
}
