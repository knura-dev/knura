package io.knura.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNamesTest {
    private val desk = "04356B02936980"
    private val door = "04AABBCCDDEEFF"

    private fun shared(uid: String, name: String?) = Snapshot.TagInfo(uid, 1, name, shared = true)

    @Test
    fun takesTheAccountsNamesWhenNothingWaitsToBeSent() {
        val names = TagNames(JSONObject().put(desk, "Desk"), JSONObject())
        assertTrue(names.adopt(listOf(shared(desk, "Office desk"), shared(door, "Front door"))))
        assertEquals("Office desk", names.name(desk))
        assertEquals("Front door", names.name(door))
        assertTrue(names.adopt(listOf(shared(desk, null))))
        assertNull(names.name(desk))
        assertFalse(names.adopt(listOf(shared(door, "Front door"))))
    }

    @Test
    fun aServerThatDoesntShareNamesLeavesThemAlone() {
        val names = TagNames(JSONObject().put(desk, "Desk"), JSONObject())
        assertFalse(names.adopt(listOf(Snapshot.TagInfo(desk, 1))))
        assertEquals("Desk", names.name(desk))
    }

    @Test
    fun aRenameOnThePhoneWaitsAndIsntUndoneByTheAccountsOlderName() {
        val names = TagNames(JSONObject(), JSONObject())
        names.rename(desk, "  Desk  ", shared = true, now = 1000)
        assertEquals("Desk", names.name(desk))
        val send = names.toSend(listOf(desk)).single()
        assertEquals(TagNames.Send(desk, "Desk", 1000), send)
        assertEquals(1000L, send.body().getLong("changedAt"))

        // A refresh before it's sent keeps the phone's name.
        names.adopt(listOf(shared(desk, "Old")))
        assertEquals("Desk", names.name(desk))

        // The account had a newer name, so it says that one stands.
        names.sent(send, "Newer from the dashboard")
        assertEquals("Newer from the dashboard", names.name(desk))
        assertTrue(names.toSend(listOf(desk)).isEmpty())
    }

    @Test
    fun aRenameMadeWhileSendingStillWaits() {
        val names = TagNames(JSONObject(), JSONObject())
        names.rename(desk, "Desk", shared = true, now = 1000)
        val send = names.toSend(listOf(desk)).single()
        names.rename(desk, "Desk 2", shared = true, now = 2000)
        names.sent(send, "Desk")
        assertEquals("Desk 2", names.name(desk))
        assertEquals(2000L, names.toSend(listOf(desk)).single().at)
    }

    @Test
    fun namesFromBeforeAreSentAtTimeZeroOnce() {
        val names = TagNames(JSONObject().put(desk, "Desk").put(door, "Door"), JSONObject())
        names.rename(door, "Back door", shared = true, now = 5000)
        names.shareOldNames(listOf(desk, door))
        val sends = names.toSend(listOf(desk, door)).associateBy { it.uid }
        assertEquals(0L, sends.getValue(desk).at)
        assertEquals(5000L, sends.getValue(door).at)
    }

    @Test
    fun clearingANameAndForgettingGoneTags() {
        val names = TagNames(JSONObject().put(desk, "Desk"), JSONObject())
        names.rename(desk, "", shared = true, now = 1000)
        assertNull(names.name(desk))
        assertEquals("", names.toSend(listOf(desk)).single().name)
        names.forgetGone(listOf(door))
        assertTrue(names.toSend(listOf(desk)).isEmpty())
        names.rename(door, "x".repeat(60), shared = false, now = 1)
        assertEquals(40, names.name(door)!!.length)
        assertTrue(names.toSend(listOf(door)).isEmpty())
    }
}
