package io.knura.android

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * What a pairing QR code holds: the server's address, the login token, and for knurd the SHA-256
 * of its TLS certificate.
 *
 * - From `knurctl admin pair-phone`: `knura://pair?url=https%3A%2F%2F192.168.1.20%3A8425&fp=<64 hex>&token=knurd_...`.
 *   knurd's certificate is self-signed, so the app trusts only that certificate.
 * - From a hosted Knura account: `knura://pair?url=https%3A%2F%2F<host>%2Fo%2F<owner>&token=knurd_...`,
 *   with no `fp`. A hosted server has an ordinary public certificate, checked the usual way.
 */
data class Pairing(val url: String, val fingerprint: String, val token: String) {
    /** True for a hosted account: no pinned certificate, the phone's usual certificate checks instead. */
    val hosted: Boolean get() = fingerprint.isEmpty()

    companion object {
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
        private val TOKEN = Regex("knurd_[A-Za-z0-9_-]{43}")
        /** A hosted account's address ends in /o/ and its owner ID, as on tap.knura.io. */
        private val HOSTED_PATH = Regex("/o/[a-km-np-z2-9]{10}")
        private val IP_ADDRESS = Regex("[0-9.]+|\\[.*]")

        /** Throws [IllegalArgumentException], with a message for people, if it isn't a pairing link. */
        fun parse(text: String): Pairing {
            val notOurs = "That's not a Knura pairing code. On knurd's computer, run: knurctl admin pair-phone <name>," +
                " or pair a phone on your hosted account's page"
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
                throw IllegalArgumentException("The code's server address isn't a valid URL: $url")
            }
            val token = params["token"] ?: ""
            require(TOKEN.matches(token)) { "The code has no valid login token" }
            val fingerprint = params["fp"]
            if (fingerprint == null) {
                // A hosted account: a public host name with a certificate the phone already trusts.
                require(parsed.scheme == "https" && parsed.host != null && !IP_ADDRESS.matches(parsed.host) &&
                    parsed.port == -1 && HOSTED_PATH.matches(parsed.rawPath ?: "") && parsed.rawQuery == null) {
                    "The code's hosted account address must look like https://<host>/o/<owner ID>, not $url"
                }
                return Pairing(url, "", token)
            }
            require(parsed.scheme == "https" && parsed.host != null && parsed.rawPath.isNullOrEmpty()) {
                "The code's knurd address must look like https://192.168.1.20:8425, not $url"
            }
            require(FINGERPRINT.matches(fingerprint)) { "The code has no valid certificate fingerprint" }
            return Pairing(url, fingerprint, token)
        }
    }
}
