package io.knura.android

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * Tapping a tag with the app: the app reads the tag's link itself and sends it to the server
 * signed with this phone's tap key, so the server knows which phone tapped. Must match
 * `io.knura.core.tap.TapSignature` in knura-core.
 */
object SignedTap {
    const val EVERYDAY = "everyday"
    const val CONFIRM = "confirm"
    const val RESOLVER_HOST = "tap.knura.io"

    /**
     * Where this phone's server wants signed taps, as its `GET /v1/tap-keys` said: [tapUrl] (null
     * when it can't take them from outside yet) and the [owner] ID in its tap.knura.io links.
     */
    data class Route(val phone: String, val tapUrl: String?, val owner: String?) {
        fun toJson(): JSONObject = JSONObject().put("phone", phone).put("tapUrl", tapUrl ?: JSONObject.NULL)
            .put("owner", owner ?: JSONObject.NULL)

        companion object {
            fun fromJson(json: JSONObject): Route = Route(json.getString("phone"),
                json.optString("tapUrl").takeIf { json.has("tapUrl") && !json.isNull("tapUrl") && it.isNotEmpty() },
                json.optString("owner").takeIf { json.has("owner") && !json.isNull("owner") && it.isNotEmpty() })
        }
    }

    /** A tag link this phone's server takes: where to send it, and the link's query to sign. */
    data class Target(val url: String, val query: String)

    /**
     * Whether [link], read from a tag, is one of this phone's server's tags, and where to send it.
     * Null for anyone else's tag (it opens in the browser as usual) or for a link that isn't a tap.
     */
    fun target(link: String, route: Route): Target? {
        val tapUrl = route.tapUrl ?: return null
        val uri = try {
            URI(link.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val query = uri.rawQuery ?: return null
        if (uri.scheme != "https" || uri.rawFragment != null || !saneQuery(query)) return null
        val owner = route.owner
        if (uri.host == RESOLVER_HOST && uri.port == -1 && owner != null && uri.rawPath == "/$owner") {
            return Target(tapUrl, query)
        }
        val base = "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}"
        return if (base == tapUrl) Target(tapUrl, query) else null
    }

    /** What the tap key signs. */
    fun message(query: String, phone: String, epochSeconds: Long, kind: String): ByteArray =
        "knura-tap-v1\n$query\n$phone\n$epochSeconds\n$kind".toByteArray(Charsets.UTF_8)

    /** The body of `POST <tapUrl>`. */
    fun body(query: String, phone: String, epochSeconds: Long, kind: String, signature: String): JSONObject =
        JSONObject().put("link", query).put("phone", phone).put("time", epochSeconds).put("key", kind)
            .put("sig", signature)

    /** Sends a signed tap; the server's HTTP status and JSON answer. Throws [IOException] if it can't be reached. */
    fun send(url: String, body: JSONObject): Pair<Int, JSONObject> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                JSONObject()
            }
            return code to json
        } finally {
            connection.disconnect()
        }
    }

    /** The server's answer to a signed tap, for the result screen. */
    fun describe(code: Int, json: JSONObject): TagResults.Result {
        val rows = mutableListOf<Pair<String, String>>()
        json.optString("uid").takeIf { it.isNotEmpty() }?.let { rows += "Tag" to it }
        if (json.has("counter")) rows += "Tap number" to json.optInt("counter").toString()
        json.optJSONObject("tapper")?.let { rows += "Signed by" to it.optString("phone") }
        val actions = json.optInt("actions", 0)
        return when (code) {
            200 -> TagResults.Result(true, "Done",
                when (actions) {
                    0 -> "Tap verified. This tag has no action set up yet."
                    1 -> "Tap verified. Started 1 action."
                    else -> "Tap verified. Started $actions actions."
                }, rows)
            409 -> TagResults.Result(false, "Already used", "This tap was already used. Hold the tag to the phone again.", rows)
            403 -> if (json.optString("status") == "refused") {
                TagResults.Result(false, "Not allowed", "This phone isn't on the list of phones that may use this tag. Change it under Who can use this tag in your dashboard.", rows)
            } else {
                TagResults.Result(false, "Not a genuine tap", "Your server couldn't verify this tag.", rows)
            }
            401 -> TagResults.Result(false, "This phone wasn't accepted",
                json.optString("detail").ifEmpty { "Pair this phone with your server again." }, rows)
            429 -> TagResults.Result(false, "Too many taps", "Wait a minute, then try again.", rows)
            else -> TagResults.Result(false, "Didn't work", json.optString("detail").ifEmpty { "Your server answered $code." }, rows)
        }
    }

    /**
     * The tap link for a hand-off from the browser's "Open in Knura" button
     * (`knura://tap?q=<the tap link's query>`), rebuilt on this phone's own server address so a web
     * page can't point the app anywhere else. Null if it isn't such a link or the phone has no server address.
     */
    fun handoffLink(uri: String, route: Route): String? {
        val tapUrl = route.tapUrl ?: return null
        val parsed = try {
            URI(uri.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        if (parsed.scheme != "knura" || parsed.host != "tap") return null
        val q = parsed.rawQuery?.split('&')?.firstOrNull { it.startsWith("q=") }?.substring(2) ?: return null
        val query = try {
            java.net.URLDecoder.decode(q, "UTF-8")
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!saneQuery(query) || !query.matches(Regex("[A-Za-z0-9=&_.~-]+"))) return null
        return tapUrl + "?" + query
    }

    /** One short ASCII line, as the server accepts. */
    fun saneQuery(query: String): Boolean =
        query.isNotEmpty() && query.length <= 512 && query.all { it.code in 0x21..0x7e }
}
