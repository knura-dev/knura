package io.knura.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagResultsTest {
    @Test
    fun provision() {
        val r = TagResults.describe("provision",
            JSONObject("""{"uid":"04406B02936980","tapUrl":"https://tap.knura.io/zack"}"""))
        assertTrue(r.good)
        assertEquals("Tag is set up", r.title)
        assertEquals("UID" to "04406B02936980", r.rows[0])
        assertEquals("Opens" to "https://tap.knura.io/zack", r.rows[2])
    }

    @Test
    fun tapThatDoesNotVerify() {
        val r = TagResults.describe("tap",
            JSONObject("""{"uid":"04406B02936980","genuine":false,"why":"replayed counter","url":"https://x"}"""))
        assertFalse(r.good)
        assertEquals("replayed counter", r.subtitle)
    }

    @Test
    fun infoOnAFactoryTag() {
        val r = TagResults.describe("info", JSONObject("""{"uid":"04406B02936980","keys":"factory","url":null}"""))
        assertEquals(listOf("UID" to "04406B02936980", "Keys" to "Factory keys (ready to set up)", "Link" to "No link"), r.rows)
    }
}
