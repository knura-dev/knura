package io.knura.android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.json.JSONException
import org.json.JSONObject

/**
 * Talks to knurd's admin API over HTTPS, trusting only the certificate whose SHA-256 the pairing
 * code carried, as knurctl does with --creds. knurd's certificate is self-signed and names no
 * host, so the pin replaces the usual certificate authority and host name checks.
 */
class KnurdClient(private val pairing: Pairing) {
    /** An answer from knurd: the HTTP status and its JSON body. */
    data class Reply(val code: Int, val json: JSONObject)

    val url: String get() = pairing.url

    private val tls: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(PinnedTrustManager(pairing.fingerprint)), null)
    }

    fun get(path: String): Reply = send(path, null)

    fun post(path: String, body: JSONObject): Reply = send(path, body)

    private fun send(path: String, body: JSONObject?): Reply {
        val connection = URL(pairing.url + path).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = tls.socketFactory
            // The certificate is pinned, so whatever name the connection used, it is knurd.
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connection.connectTimeout = 5000
            // knurd waits up to 20 seconds for the tag's next step before answering.
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer " + pairing.token)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                JSONObject().put("status", "error").put("detail", "knurd answered $code without JSON")
            }
            return Reply(code, json)
        } finally {
            connection.disconnect()
        }
    }
}

/** Accepts exactly one server certificate, by the SHA-256 of its encoded form. */
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("knurd sent no certificate")
        if (sha256Hex(leaf.encoded) != fingerprint) {
            throw CertificateException(
                "knurd's certificate isn't the one this phone was paired with. If you made a new one," +
                    " pair again; otherwise something else is answering at that address")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("not used for client certificates")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex().lowercase()
    }
}

/** Why a request to knurd failed, in words for people. */
fun explain(e: IOException, url: String): String = when (e) {
    is javax.net.ssl.SSLHandshakeException -> e.cause?.message ?: e.message ?: "TLS failed"
    is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
        "Can't reach knurd at $url. Is the phone on the same Wi-Fi, knurd running, and port 8425 open in the firewall?"
    else -> e.message ?: e.toString()
}
