package io.knura.android

import org.json.JSONObject

/** knurd's read-only admin answers as text for the screen, like knurctl admin status, tags and taps. */
object KnurdViews {
    fun status(s: JSONObject): String {
        val up = s.optLong("uptimeSeconds")
        val keys = if (s.optString("keys") == "master") "one master key, per-tag keys derived" else "fixed SDM keys"
        val publicUrl = if (s.isNull("publicUrl")) "not set (phones can't reach knurd)" else s.optString("publicUrl")
        val resolver = if (s.isNull("resolverOwner")) "not registered" else "owner " + s.optString("resolverOwner")
        return """
            knurd is up (for ${up / 86400}d ${up / 3600 % 24}h ${up / 60 % 60}m)
            Keys:        $keys
            Public URL:  $publicUrl
            Resolver:    $resolver
            Knots:       ${s.optInt("knots")} with actions
            Tags seen:   ${s.optInt("tags")}
        """.trimIndent()
    }

    fun tags(json: JSONObject): String {
        val tags = json.optJSONArray("tags")
        if (tags == null || tags.length() == 0) return "knurd hasn't accepted a tap from any tag yet."
        val out = StringBuilder("UID              LAST COUNTER\n")
        for (i in 0 until tags.length()) {
            val t = tags.getJSONObject(i)
            out.append(t.optString("uid").padEnd(17)).append(t.optInt("lastCounter")).append('\n')
        }
        return out.toString().trimEnd()
    }

    fun taps(json: JSONObject): String {
        val taps = json.optJSONArray("taps")
        if (taps == null || taps.length() == 0) return "No taps since knurd started."
        val out = StringBuilder()
        for (i in 0 until taps.length()) {
            val t = taps.getJSONObject(i)
            // 2026-10-22T02:20:58Z -> 10-22 02:20:58 UTC, so a line fits a phone screen.
            val time = t.optString("time").replace('T', ' ').removeSuffix("Z").let { if (it.length > 5) it.substring(5) else it }
            val uid = if (t.isNull("uid")) "-" else t.optString("uid")
            val counter = if (t.has("counter")) " #" + t.optInt("counter") else ""
            out.append("$time  ${t.optString("status")}  $uid$counter  actions ${t.optInt("actions")}\n")
        }
        return "Newest first, times in UTC:\n" + out.toString().trimEnd()
    }
}
