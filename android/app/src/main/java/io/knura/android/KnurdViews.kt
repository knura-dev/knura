package io.knura.android

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.json.JSONObject

/** knurd's read-only admin answers as rows for the screen, like knurctl admin status, tags and taps. */
object KnurdViews {
    /** How a row's badge looks: green for good news, red for bad. */
    enum class Badge { GOOD, BAD }

    /** One line in a list: [title] on top, [detail] under it, an optional [badge] on the right. */
    data class Row(
        val title: String,
        val detail: String = "",
        val badge: String? = null,
        val badgeKind: Badge = Badge.GOOD,
        val mono: Boolean = false,
    )

    /** What a knurd screen shows: an optional [note] above the rows, or [empty] when there are none. */
    data class Answer(val rows: List<Row>, val note: String? = null, val empty: String? = null)

    fun status(s: JSONObject): Answer {
        val up = s.optLong("uptimeSeconds")
        val keys = if (s.optString("keys") == "master") "One master key, per-tag keys derived" else "Fixed SDM keys"
        val publicUrl = if (s.isNull("publicUrl")) "Not set (phones can't reach knurd)" else s.optString("publicUrl")
        val resolver = if (s.isNull("resolverOwner")) "Not registered" else "Owner " + s.optString("resolverOwner")
        return Answer(listOf(
            Row("Up for", "${up / 86400}d ${up / 3600 % 24}h ${up / 60 % 60}m"),
            Row("Keys", keys),
            Row("Public URL", publicUrl, mono = !s.isNull("publicUrl")),
            Row("Resolver", resolver),
            Row("Knots", "${s.optInt("knots")} with actions"),
            Row("Tags seen", s.optInt("tags").toString()),
        ))
    }

    fun tags(json: JSONObject): Answer {
        val tags = json.optJSONArray("tags")
        val rows = (0 until (tags?.length() ?: 0)).map {
            val t = tags!!.getJSONObject(it)
            Row(t.optString("uid"), "Last counter ${t.optInt("lastCounter")}", mono = true)
        }
        return Answer(rows, empty = "knurd hasn't accepted a tap from any tag yet.")
    }

    fun taps(json: JSONObject, zone: ZoneId = ZoneId.systemDefault()): Answer {
        val taps = json.optJSONArray("taps")
        val rows = (0 until (taps?.length() ?: 0)).map {
            val t = taps!!.getJSONObject(it)
            val uid = if (t.isNull("uid")) "No UID" else t.optString("uid")
            val counter = if (t.has("counter")) "#" + t.optInt("counter") + " · " else ""
            val actions = when (val n = t.optInt("actions")) {
                0 -> "no actions"
                1 -> "1 action"
                else -> "$n actions"
            }
            val (badge, kind) = when (val status = t.optString("status")) {
                "ok" -> "Genuine" to Badge.GOOD
                "replay" -> "Replay" to Badge.BAD
                "rejected" -> "Rejected" to Badge.BAD
                "malformed" -> "Malformed" to Badge.BAD
                else -> status.replaceFirstChar { c -> c.uppercase() } to Badge.BAD
            }
            Row(uid, "${time(t.optString("time"), zone)} · $counter$actions", badge, kind, mono = !t.isNull("uid"))
        }
        return Answer(rows, note = "Newest first, in your phone's time", empty = "No taps since knurd started.")
    }

    private val SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    /** 2026-10-22T02:20:58Z -> 10-22 02:20:58 in [zone], so a line fits a phone screen. */
    fun time(iso: String, zone: ZoneId): String = try {
        SHORT_TIME.format(Instant.parse(iso).atZone(zone))
    } catch (e: DateTimeParseException) {
        iso
    }

    /** The names from knurd's `GET /v1/tokens`. */
    fun tokenNames(json: JSONObject): List<String> {
        val tokens = json.optJSONArray("tokens") ?: return emptyList()
        return (0 until tokens.length()).map { tokens.getString(it) }
    }
}
