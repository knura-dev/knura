package io.knura.android

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * What `knurctl admin pair-phone` puts in its QR code: knurd's admin API address, the SHA-256 of
 * its TLS certificate (the app trusts only that certificate) and the login token.
 * The link looks like `knura://pair?url=https%3A%2F%2F192.168.1.20%3A8425&fp=<64 hex>&token=knurd_...`.
 */
data class Pairing(val url: String, val fingerprint: String, val token: String) {
    companion object {
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
        private val TOKEN = Regex("knurd_[A-Za-z0-9_-]{43}")

        /** Throws [IllegalArgumentException], with a message for people, if it isn't a pairing link. */
        fun parse(text: String): Pairing {
            val notOurs = "That's not a Knura pairing code. On knurd's computer, run: knurctl admin pair-phone <name>"
            val uri = try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException(notOurs)
            }
            require(uri.scheme == "knura" && uri.host == "pair") { notOurs }
            val params = HashMap<String, String>()
            for (pair in (uri.rawQuery ?: "").split("&")) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    params[pair.substring(0, eq)] = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                }
            }
            val url = params["url"]?.trimEnd('/') ?: throw IllegalArgumentException(notOurs)
            val parsed = try {
                URI(url)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("The code's knurd address isn't a valid URL: $url")
            }
            require(parsed.scheme == "https" && parsed.host != null && parsed.rawPath.isNullOrEmpty()) {
                "The code's knurd address must look like https://192.168.1.20:8425, not $url"
            }
            val fingerprint = params["fp"] ?: ""
            require(FINGERPRINT.matches(fingerprint)) { "The code has no valid certificate fingerprint" }
            val token = params["token"] ?: ""
            require(TOKEN.matches(token)) { "The code has no valid knurd token" }
            return Pairing(url, fingerprint, token)
        }
    }
}
