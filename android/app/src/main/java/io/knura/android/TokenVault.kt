package io.knura.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the pairing on this phone. The token is encrypted with an Android Keystore key that can
 * only be used for 30 seconds after you unlock with your fingerprint or screen lock, and never
 * leaves the phone's secure hardware. A new fingerprint wipes the key, so the phone must be paired
 * again.
 */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    val url: String? get() = prefs.getString("url", null)

    fun isPaired(): Boolean = prefs.contains("token")

    /** Stores a pairing. Call within 30 seconds of the person unlocking. */
    fun save(pairing: Pairing) {
        deleteKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .build())
        val key = generator.generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val sealed = cipher.doFinal(pairing.token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("url", pairing.url)
            .putString("fingerprint", pairing.fingerprint)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
    }

    /** The stored pairing, token decrypted. Call within 30 seconds of the person unlocking. */
    fun load(): Pairing {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY, null) as SecretKey?
            ?: throw IllegalStateException("This phone's pairing key is gone; pair again")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode("iv")))
        val token = cipher.doFinal(decode("token")).toString(Charsets.UTF_8)
        return Pairing(prefs.getString("url", null)!!, prefs.getString("fingerprint", null)!!, token)
    }

    fun forget() {
        prefs.edit().clear().apply()
        deleteKey()
    }

    private fun decode(name: String): ByteArray = Base64.decode(prefs.getString(name, null)!!, Base64.NO_WRAP)

    private fun deleteKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY)) {
            keyStore.deleteEntry(KEY)
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY = "knura-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
