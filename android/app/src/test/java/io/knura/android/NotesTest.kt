package io.knura.android

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private val t0 = Instant.parse("2026-10-31T12:00:00Z")
    private val t1 = Instant.parse("2026-10-31T13:00:00Z")

    private fun snap(at: Instant, tags: String, taps: String, tokens: String) = Snapshot.of(at, null,
        JSONObject("""{"tags":[$tags]}"""), JSONObject("""{"taps":[$taps]}"""), JSONObject("""{"tokens":[$tokens],"you":"fold7"}"""))

    private val before = snap(t0, """{"uid":"04356B02936980","lastCounter":3}""",
        """{"time":"2026-10-31T11:00:00Z","status":"rejected","uid":"04356B02936980","actions":0}""", "\"fold7\",\"laptop\"")

    @Test
    fun nothingOnTheFirstLook() {
        assertTrue(Note.changes(null, before) { null }.isEmpty())
    }

    @Test
    fun findsNewRejectedTapsTagsAndTokens() {
        val after = snap(t1, """{"uid":"04356B02936980","lastCounter":4},{"uid":"04AABBCCDDEEFF","lastCounter":1}""",
            """{"time":"2026-10-31T12:30:00Z","status":"ok","uid":"04356B02936980","counter":4,"actions":1},""" +
                """{"time":"2026-10-31T12:40:00Z","status":"rejected","uid":"04F3D800009B02","actions":0},""" +
                """{"time":"2026-10-31T12:41:00Z","status":"replay","uid":"04356B02936980","actions":0},""" +
                """{"time":"2026-10-31T11:00:00Z","status":"rejected","uid":"04356B02936980","actions":0}""",
            "\"fold7\",\"kitchen-tablet\"")
        val notes = Note.changes(before, after) { if (it == "04356B02936980") "Front door" else null }
        assertEquals(listOf("Rejected tap", "Replayed tap", "Tag set up", "New token", "Token removed"), notes.map { it.title })
        assertEquals("An unknown tag (04F3D8…9B02) failed the signature check. Nothing ran.", notes[0].body)
        assertEquals("Front door was tapped with a link that was already used. Nothing ran.", notes[1].body)
        assertTrue(notes[3].body.startsWith("kitchen-tablet"))
        assertTrue(notes[4].body.startsWith("laptop"))
    }

    @Test
    fun keepsNewestFirstAndSurvivesSaving() {
        val a = Note(Note.Kind.WARN, "Server went offline", "x", t0, unread = false)
        val b = Note(Note.Kind.OK, "Server is back online", "y", t1)
        val merged = Note.merge(listOf(a), listOf(b))
        assertEquals(listOf(b, a), merged)
        assertEquals(merged, Note.listFromJson(Note.listToJson(merged)))
        assertFalse(Note.listFromJson(Note.listToJson(merged))[1].unread)
        assertTrue(Note.listFromJson("nonsense").isEmpty())
        val many = (0 until 80).map { Note(Note.Kind.INFO, "n$it", "", t0.plusSeconds(it.toLong())) }
        assertEquals(Note.KEEP, Note.merge(emptyList(), many).size)
        assertEquals("n79", Note.merge(emptyList(), many)[0].title)
    }
}
