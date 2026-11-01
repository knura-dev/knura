package io.knura.android

import org.json.JSONObject

/**
 * Tag names, kept on the phone, and for a hosted account shared with its dashboard so a rename in
 * either place shows in both.
 *
 * The rule when both change: the newest change wins. A rename on the phone is kept as "still to
 * send" with the time it was made, and sent on the next refresh. The account keeps it only if the
 * tag wasn't renamed on the dashboard after that time, and answers with the name that stands,
 * which the phone then takes. Tags with nothing waiting to send take the account's name on every
 * refresh. knurd doesn't keep names, so with your own server names stay on this phone only.
 *
 * [names] maps UID to name. [pending] maps UID to `{"name": "..", "at": <ms>}`; an empty name
 * takes the name off. A name kept from before sharing is sent with `at` 0, so it only fills in a
 * tag the dashboard never named.
 */
class TagNames(val names: JSONObject, val pending: JSONObject) {
    /** One rename to send: the body for `POST /v1/tags/<uid>`. */
    data class Send(val uid: String, val name: String, val at: Long) {
        fun body(): JSONObject = JSONObject().put("name", name).put("changedAt", at)
    }

    fun name(uid: String): String? = names.optString(uid, "").ifEmpty { null }

    /** A rename made on this phone now. When [shared], it also waits to be sent. */
    fun rename(uid: String, name: String, shared: Boolean, now: Long) {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) names.remove(uid) else names.put(uid, clean)
        if (shared) pending.put(uid, JSONObject().put("name", clean).put("at", now))
    }

    /**
     * The first time a server shares names: queues each name this phone already had, for a tag
     * the server lists, at time 0 (so a dashboard name wins over it).
     */
    fun shareOldNames(uids: Collection<String>) {
        for (uid in uids) {
            val name = name(uid) ?: continue
            if (!pending.has(uid)) pending.put(uid, JSONObject().put("name", name).put("at", 0L))
        }
    }

    /** Renames waiting to be sent, for tags the server still lists. */
    fun toSend(uids: Collection<String>): List<Send> = uids.mapNotNull { uid ->
        pending.optJSONObject(uid)?.let { Send(uid, it.optString("name"), it.optLong("at")) }
    }

    /** Drops waiting renames for tags the server no longer lists, which can never be sent. */
    fun forgetGone(uids: Collection<String>) {
        pending.keys().asSequence().toList().filter { it !in uids }.forEach { pending.remove(it) }
    }

    /**
     * The account's answer to [sent]: takes the name that stands, and stops waiting to send it,
     * unless it was renamed again on this phone meanwhile (then that newer rename still waits).
     */
    fun sent(sent: Send, standing: String?) {
        val now = pending.optJSONObject(sent.uid)
        if (now != null && (now.optLong("at") != sent.at || now.optString("name") != sent.name)) return
        pending.remove(sent.uid)
        setName(sent.uid, standing)
    }

    /** The account no longer knows the tag, so there is nothing to send. */
    fun dropped(uid: String) {
        pending.remove(uid)
    }

    /**
     * Takes the server's names for tags with nothing waiting to send, and returns true if any name
     * changed. Tags from a server that doesn't share names are left alone.
     */
    fun adopt(tags: List<Snapshot.TagInfo>): Boolean {
        var changed = false
        for (tag in tags) {
            if (!tag.shared || pending.has(tag.uid)) continue
            if (name(tag.uid) != tag.name?.ifEmpty { null }) {
                setName(tag.uid, tag.name)
                changed = true
            }
        }
        return changed
    }

    private fun setName(uid: String, name: String?) {
        if (name.isNullOrEmpty()) names.remove(uid) else names.put(uid, name)
    }

    companion object {
        const val MAX_NAME = 40
    }
}
