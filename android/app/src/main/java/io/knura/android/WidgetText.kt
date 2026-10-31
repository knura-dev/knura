package io.knura.android

import java.time.Instant
import java.time.ZoneId

/** The words on the home screen widgets, from the app's last look at the server. */
object WidgetText {
    data class LastTap(val title: String, val detail: String, val ok: Boolean?)

    data class Today(val count: String, val detail: String)

    fun lastTap(s: Snapshot?, name: (String) -> String?, zone: ZoneId, now: Instant = Instant.now()): LastTap {
        if (s == null) return LastTap("No taps yet", "Open Knura to load your taps", null)
        val tap = s.taps.maxByOrNull { it.time } ?: return LastTap("No taps yet", "Checked ${soft(Words.ago(s.at, zone, now))}", null)
        val uid = tap.uid
        val title = if (uid == null) "Unknown tag" else name(uid) ?: Words.shortUid(uid)
        return LastTap(title, "${status(tap.status)} · ${soft(Words.ago(tap.time, zone, now))}", tap.ok)
    }

    fun today(s: Snapshot?, zone: ZoneId, now: Instant = Instant.now()): Today {
        if (s == null) return Today("–", "Open Knura to load")
        val day = now.atZone(zone).toLocalDate()
        val taps = s.taps.filter { it.time.atZone(zone).toLocalDate() == day }
        val rejected = taps.count { !it.ok }
        val words = if (taps.size == 1) "tap" else "taps"
        return Today(taps.size.toString(), if (rejected == 0) words else "$words · $rejected rejected")
    }

    fun status(status: String) = when (status) {
        "ok" -> "Verified"
        "replay" -> "Replayed"
        "rejected" -> "Rejected"
        "malformed" -> "Malformed"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    /** "Just now" and "Yesterday" read better in lower case after a dot; dates keep their capital. */
    private fun soft(ago: String) = if (ago == "Just now" || ago == "Yesterday" || ago.endsWith("ago")) ago.replaceFirstChar { it.lowercase() } else ago
}
