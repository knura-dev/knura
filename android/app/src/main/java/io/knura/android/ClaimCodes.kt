package io.knura.android

import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Reads the claim code on a ready-to-use tag's claim card, the same way knurd's ClaimCode does,
 * so a typo shows up before the tag is at the phone. knurd checks it again and works out the keys;
 * the phone only passes the code on.
 *
 * The card shows 28 Crockford base32 characters in groups of four; its QR code holds
 * `knura://claim?uid=<UID>&code=<code>`. Case, spaces and dashes don't matter, O reads as 0, and
 * I and L read as 1.
 */
object ClaimCodes {
    /** [code] is the 28 characters without dashes; [uid] is the card's tag UID in hex, when the QR code gave one. */
    data class Claim(val code: String, val uid: String?)

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val SECRET_CHARS = 26
    private val UID = Regex("[0-9A-F]{14}")

    /** Throws [IllegalArgumentException], with a message for people, if it isn't a claim code. */
    fun parse(text: String): Claim {
        var code = text.trim()
        var uid: String? = null
        if (code.startsWith("knura://", ignoreCase = true)) {
            val params = linkParams(code)
            code = params["code"] ?: throw IllegalArgumentException("That's not a Knura claim card's QR code.")
            uid = params["uid"]?.uppercase()?.takeIf { UID.matches(it) }
        }
        val chars = StringBuilder()
        for (c in code.uppercase()) {
            when (c) {
                '-', ' ' -> {}
                'O' -> chars.append('0')
                'I', 'L' -> chars.append('1')
                else -> {
                    require(ALPHABET.indexOf(c) >= 0) { "A claim code can't contain '$c'." }
                    chars.append(c)
                }
            }
        }
        require(chars.length == SECRET_CHARS + 2) { "A claim code has 28 letters and digits; this has ${chars.length}." }
        var value = BigInteger.ZERO
        for (i in 0 until SECRET_CHARS) {
            value = value.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(chars[i]).toLong()))
        }
        require(value.bitLength() <= 128) { "That claim code doesn't check out. Look for a typo." }
        val raw = value.toByteArray()
        val secret = ByteArray(16)
        val copy = minOf(raw.size, 16)
        System.arraycopy(raw, raw.size - copy, secret, 16 - copy, copy)
        val digest = MessageDigest.getInstance("SHA-256").digest(secret)
        secret.fill(0)
        val bits = ((digest[0].toInt() and 0xFF) shl 2) or ((digest[1].toInt() and 0xFF) ushr 6)
        val check = "" + ALPHABET[bits ushr 5] + ALPHABET[bits and 31]
        require(chars.substring(SECRET_CHARS) == check) { "That claim code doesn't check out. Look for a typo." }
        return Claim(chars.toString(), uid)
    }

    private fun linkParams(link: String): Map<String, String> {
        val uri = try {
            URI(link.replace(" ", ""))
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("That's not a Knura claim card's QR code.")
        }
        require(uri.scheme.equals("knura", ignoreCase = true) && uri.host.equals("claim", ignoreCase = true)) {
            "That's not a Knura claim card's QR code."
        }
        val params = HashMap<String, String>()
        for (pair in (uri.rawQuery ?: "").split("&")) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                params[pair.substring(0, eq)] = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
        }
        return params
    }
}
