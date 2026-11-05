package io.knura.android

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.io.IOException

/** Reads the link a tag holds, as Android would before opening it in the browser. */
object TagLink {
    /** The first link in the tag that opened the app. */
    fun fromIntent(intent: Intent): String? {
        @Suppress("DEPRECATION")
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        return messages.filterIsInstance<NdefMessage>().firstNotNullOfOrNull { linkIn(it) }
    }

    /**
     * Reads an NTAG 424 DNA's link file over ISO-DEP, for a tag held to the phone while the app is
     * open (reader mode skips Android's own read). Reading it makes the tag write a new tap, as a
     * phone reading it to open the browser does. Null if it isn't such a tag or holds no link.
     */
    fun read(tag: Tag): String? {
        val iso = IsoDep.get(tag) ?: return null
        return try {
            iso.connect()
            iso.timeout = 2000
            if (!ok(iso.transceive(SELECT_NDEF_APP)) || !ok(iso.transceive(SELECT_NDEF_FILE))) return null
            val head = iso.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02))
            if (!ok(head) || head.size < 4) return null
            val length = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
            if (length !in 1..250) return null
            val body = iso.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x02, length.toByte()))
            if (!ok(body) || body.size != length + 2) return null
            linkIn(NdefMessage(body.copyOfRange(0, length)))
        } catch (e: IOException) {
            null
        } catch (e: android.nfc.FormatException) {
            null
        } finally {
            try {
                iso.close()
            } catch (e: IOException) {
                // Already gone.
            }
        }
    }

    private fun linkIn(message: NdefMessage): String? =
        message.records.firstNotNullOfOrNull { it.toUri()?.toString() }?.takeIf { it.startsWith("https://") }

    private fun ok(reply: ByteArray): Boolean =
        reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()

    /** ISO SELECT of the NFC Forum NDEF application, then of its NDEF file (E104). */
    private val SELECT_NDEF_APP = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
        0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00)
    private val SELECT_NDEF_FILE = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04)
}
