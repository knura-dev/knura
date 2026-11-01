package io.knura.android

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app last read from the server: its status, the tags it has seen, its recent taps and
 * its tokens, all from one unlock. The home screen and the tabs draw from it, and it is kept on
 * the phone so they still have something to show before the next unlock.
 */
data class Snapshot(
    val at: Instant,
    val status: Status?,
    val tags: List<TagInfo>,
    val taps: List<Tap>,
    /** Token names from `GET /v1/tokens`, or null when the server doesn't list them (a hosted account). */
    val tokens: List<String>?,
    /** This phone's own token name. */
    val you: String?,
) {
    data class Status(
        val uptimeSeconds: Long,
        val keys: String,
        val publicUrl: String?,
        val resolverOwner: String?,
        val knots: Int,
        val tags: Int,
        /** When knurd's master key was last backed up, or null when knurd doesn't say (older knurd, fixed keys). */
        val backup: Backup? = null,
    )

    /** ISO times of the last backup file and recovery sheet for the current master key, null if never. */
    data class Backup(val file: String?, val sheet: String?) {
        val any: Boolean get() = file != null || sheet != null

        /** The newer of the two dates (yyyy-mm-dd), or null. */
        fun latestDay(): String? = listOfNotNull(file, sheet).maxOrNull()?.take(10)
    }

    /**
     * [shared] is true when the server keeps tag names too (a hosted account does, knurd doesn't),
     * and then [name] is the server's name for the tag, or null for none.
     */
    data class TagInfo(val uid: String, val lastCounter: Int, val name: String? = null, val shared: Boolean = false)

    data class Tap(val time: Instant, val status: String, val uid: String?, val counter: Int?, val actions: Int) {
        val ok: Boolean get() = status == "ok"
    }

    /** Taps since midnight in [zone]. */
    fun tapsToday(zone: ZoneId, now: Instant = Instant.now()): Int {
        val today = now.atZone(zone).toLocalDate()
        return taps.count { it.time.atZone(zone).toLocalDate() == today }
    }

    /** Taps per day for the last 7 days, oldest first, today last. Only as far back as the server remembers. */
    fun lastSevenDays(zone: ZoneId, now: Instant = Instant.now()): IntArray {
        val today = now.atZone(zone).toLocalDate()
        val days = IntArray(7)
        for (tap in taps) {
            val ago = Duration.between(tap.time.atZone(zone).toLocalDate().atStartOfDay(), today.atStartOfDay()).toDays()
            if (ago in 0..6) days[6 - ago.toInt()]++
        }
        return days
    }

    /** The newest tap from [uid], if the server still remembers one. */
    fun lastTap(uid: String): Tap? = taps.filter { it.uid == uid }.maxByOrNull { it.time }

    fun toJson(): JSONObject = JSONObject()
        .put("at", at.toString())
        .put("status", status?.let {
            JSONObject().put("uptimeSeconds", it.uptimeSeconds).put("keys", it.keys)
                .put("publicUrl", it.publicUrl ?: JSONObject.NULL).put("resolverOwner", it.resolverOwner ?: JSONObject.NULL)
                .put("knots", it.knots).put("tags", it.tags)
                .apply {
                    val b = it.backup
                    if (b != null) put("backup", JSONObject().put("file", b.file ?: JSONObject.NULL).put("sheet", b.sheet ?: JSONObject.NULL))
                }
        } ?: JSONObject.NULL)
        .put("tags", JSONObject().put("tags", JSONArray(tags.map {
            JSONObject().put("uid", it.uid).put("lastCounter", it.lastCounter)
                .apply { if (it.shared) put("name", it.name ?: JSONObject.NULL) }
        })))
        .put("taps", JSONObject().put("taps", JSONArray(taps.map {
            JSONObject().put("time", it.time.toString()).put("status", it.status).put("uid", it.uid ?: JSONObject.NULL)
                .put("actions", it.actions).apply { if (it.counter != null) put("counter", it.counter) }
        })))
        .put("tokens", tokens?.let { JSONObject().put("tokens", JSONArray(it)).put("you", you ?: JSONObject.NULL) } ?: JSONObject.NULL)

    companion object {
        /** Builds a snapshot from the server's answers to status, tags, taps and tokens. */
        fun of(at: Instant, status: JSONObject?, tags: JSONObject?, taps: JSONObject?, tokens: JSONObject?): Snapshot = Snapshot(
            at = at,
            status = status?.let {
                Status(it.optLong("uptimeSeconds"), it.optString("keys"), it.nullableString("publicUrl"),
                    it.nullableString("resolverOwner"), it.optInt("knots"), it.optInt("tags"),
                    it.optJSONObject("backup")?.let { b -> Backup(b.nullableString("file"), b.nullableString("sheet")) })
            },
            tags = tags?.optJSONArray("tags").objects().map {
                TagInfo(it.optString("uid"), it.optInt("lastCounter"), it.nullableString("name"), it.has("name"))
            },
            taps = taps?.optJSONArray("taps").objects().mapNotNull {
                val time = try {
                    Instant.parse(it.optString("time"))
                } catch (e: DateTimeParseException) {
                    return@mapNotNull null
                }
                Tap(time, it.optString("status"), it.nullableString("uid"),
                    if (it.has("counter")) it.optInt("counter") else null, it.optInt("actions"))
            },
            tokens = tokens?.let { KnurdViews.tokenNames(it) },
            you = tokens?.nullableString("you"),
        )

        /** A snapshot saved by [toJson], or null if there is none or it can't be read. */
        fun fromJson(text: String?): Snapshot? {
            if (text.isNullOrEmpty()) return null
            return try {
                val j = JSONObject(text)
                of(Instant.parse(j.getString("at")), j.optJSONObject("status"), j.optJSONObject("tags"),
                    j.optJSONObject("taps"), j.optJSONObject("tokens"))
            } catch (e: Exception) {
                null
            }
        }

        private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else optString(name)

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}

/** Words for times and greetings, kept apart from the screens so they can be tested. */
object Words {
    /** Good morning from midnight to 11:59, good afternoon from 12:00 to 16:59, good evening from 17:00. */
    fun greeting(hour: Int): String = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** "Just now", "4 min ago", "3 h ago", "Yesterday", then a date like "Oct 22". */
    fun ago(then: Instant, zone: ZoneId, now: Instant = Instant.now()): String {
        val seconds = Duration.between(then, now).seconds
        val days = Duration.between(then.atZone(zone).toLocalDate().atStartOfDay(), now.atZone(zone).toLocalDate().atStartOfDay()).toDays()
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            days == 0L -> "${seconds / 3600} h ago"
            days == 1L -> "Yesterday"
            else -> DATE.format(then.atZone(zone))
        }
    }

    /** The clock time for today's taps ("9:41 PM"), "Yesterday" or a date for older ones. */
    fun clock(then: Instant, zone: ZoneId, now: Instant = Instant.now()): String {
        val days = Duration.between(then.atZone(zone).toLocalDate().atStartOfDay(), now.atZone(zone).toLocalDate().atStartOfDay()).toDays()
        return when (days) {
            0L -> TIME.format(then.atZone(zone))
            1L -> "Yesterday"
            else -> DATE.format(then.atZone(zone))
        }
    }

    /** 04356B02936980 -> 04356B…6980, so a UID fits next to a name. */
    fun shortUid(uid: String): String = if (uid.length > 12) uid.take(6) + "…" + uid.takeLast(4) else uid

    /** "1d 4h", "3h 12m" or "45m". */
    fun uptime(seconds: Long): String = when {
        seconds >= 86400 -> "${seconds / 86400}d ${seconds / 3600 % 24}h"
        seconds >= 3600 -> "${seconds / 3600}h ${seconds / 60 % 60}m"
        else -> "${seconds / 60}m"
    }

    /** What a tap's status means, for the second line of a row. */
    fun tapDetail(tap: Snapshot.Tap): String = when (tap.status) {
        "ok" -> {
            val actions = when (tap.actions) {
                0 -> "no actions"
                1 -> "1 action ran"
                else -> "${tap.actions} actions ran"
            }
            if (tap.counter != null) "Tap #${tap.counter} · $actions" else actions.replaceFirstChar { it.uppercase() }
        }
        "replay" -> "Replayed: this tap was used before"
        "rejected" -> "Signature did not match"
        "malformed" -> "Not a Knura tap link"
        else -> tap.status.replaceFirstChar { it.uppercase() }
    }
}
