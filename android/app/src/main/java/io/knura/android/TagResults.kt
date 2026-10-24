package io.knura.android

import org.json.JSONObject

/** knurd's answer at the end of a tag session, as the result screen shows it. */
object TagResults {
    /** [good] picks the green tick or the red cross; [rows] are label and value pairs. */
    data class Result(val good: Boolean, val title: String, val subtitle: String, val rows: List<Pair<String, String>>)

    /** knurd's name for an action ("provision", "info", "tap", "reset") and its `result` object. */
    fun describe(action: String, r: JSONObject): Result {
        val uid = "UID" to r.optString("uid")
        val genuine = r.has("genuine") && r.getBoolean("genuine")
        val counter = "Tap counter" to r.optInt("counter").toString()
        return when (action) {
            "provision" -> Result(true, "Tag is set up",
                "Taps from this tag now go to knurd and run its actions.",
                listOf(uid, "Keys" to "Per-tag, from knurd's master key", "Opens" to r.optString("tapUrl")))
            "reset" -> Result(true, "Tag is reset",
                "Back to factory state: all keys zero, no link.", listOf(uid))
            "tap" -> if (genuine) {
                Result(true, "Genuine tag", "knurd checked the tag's signature and counter.",
                    listOf(uid, counter, "Link" to r.optString("url")))
            } else {
                Result(false, "Does not verify", r.optString("why", "knurd couldn't verify this tag."),
                    listOf(uid, "Link" to r.optString("url")))
            }
            else -> {
                val keys = when (r.optString("keys")) {
                    "factory" -> "Factory keys (ready to set up)"
                    "knura" -> "Knura keys from your knurd"
                    else -> "Keys from other software or another master key"
                }
                val rows = mutableListOf(uid, "Keys" to keys, "Link" to (if (r.isNull("url")) "No link" else r.optString("url")))
                if (r.has("genuine")) {
                    rows += "Signature" to (if (genuine) "Genuine" else "Does NOT verify: ${r.optString("why")}")
                    if (genuine) rows += counter
                }
                Result(true, "Tag info", "What knurd read from the tag.", rows)
            }
        }
    }
}
