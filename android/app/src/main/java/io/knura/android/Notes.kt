package io.knura.android

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own notifications: what changed on the server between two looks at it, like a
 * rejected tap, a new tag or the server going offline. They are made on the phone from what the
 * admin API already returns, so they only appear once the app has looked.
 */
data class Note(val kind: Kind, val title: String, val body: String, val time: Instant, val unread: Boolean = true) {
    enum class Kind { BAD, OK, INFO, WARN }

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("title", title).put("body", body)
        .put("time", time.toString()).put("unread", unread)

    companion object {
        /** Kept newest first, at most this many. */
        const val KEEP = 50

        fun listFromJson(text: String?): List<Note> {
            if (text.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { i ->
                    val j = array.optJSONObject(i) ?: return@mapNotNull null
                    try {
                        Note(Kind.valueOf(j.getString("kind")), j.getString("title"), j.getString("body"),
                            Instant.parse(j.getString("time")), j.optBoolean("unread"))
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun listToJson(notes: List<Note>): String = JSONArray(notes.map { it.toJson() }).toString()

        /** Newest first, cut to [KEEP]. */
        fun merge(old: List<Note>, new: List<Note>): List<Note> = (new + old).sortedByDescending { it.time }.take(KEEP)

        /**
         * What is new in [next] since [before]: taps that failed, tags and tokens the server didn't
         * have. Nothing on the first look, so a fresh install doesn't start with a pile of alerts.
         * [name] gives a tag's name from this phone, or null.
         */
        fun changes(before: Snapshot?, next: Snapshot, name: (String) -> String?): List<Note> {
            if (before == null) return emptyList()
            val notes = mutableListOf<Note>()
            for (tap in next.taps) {
                if (tap.ok || !tap.time.isAfter(before.at)) continue
                val uid = tap.uid
                val who = if (uid == null) "A tag" else name(uid) ?: if (before.tags.none { it.uid == uid }) "An unknown tag (${Words.shortUid(uid)})" else Words.shortUid(uid)
                val why = when (tap.status) {
                    "replay" -> "was tapped with a link that was already used"
                    "malformed" -> "sent a link that isn't a Knura tap"
                    else -> "failed the signature check"
                }
                notes += Note(Kind.BAD, if (tap.status == "replay") "Replayed tap" else "Rejected tap", "$who $why. Nothing ran.", tap.time)
            }
            val known = before.tags.map { it.uid }.toSet()
            for (tag in next.tags) {
                if (tag.uid in known) continue
                notes += Note(Kind.OK, "Tag set up", "${name(tag.uid) ?: Words.shortUid(tag.uid)} has Knura keys and your server has seen it.", next.at)
            }
            val oldTokens = before.tokens
            val newTokens = next.tokens
            if (oldTokens != null && newTokens != null) {
                for (token in newTokens - oldTokens.toSet()) {
                    notes += Note(Kind.INFO, "New token", "$token can now use your server's admin API.", next.at)
                }
                for (token in oldTokens - newTokens.toSet()) {
                    notes += Note(Kind.WARN, "Token removed", "$token can't use your server any more.", next.at)
                }
            }
            return notes
        }
    }
}
