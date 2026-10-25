package io.knura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Uses the code knura-core's ClaimCodeTest pins, so the app and knurd read cards the same way. */
class ClaimCodesTest {
    private val code = "0004-1061-050R-3GG2-8A1C-60T3-GFQS"
    private val plain = "00041061050R3GG28A1C60T3GFQS"

    @Test
    fun readsCodesTheWayPeopleTypeThem() {
        assertEquals(ClaimCodes.Claim(plain, null), ClaimCodes.parse(code))
        assertEquals(plain, ClaimCodes.parse(code.lowercase().replace("-", " ")).code)
        assertEquals(plain, ClaimCodes.parse(" " + plain.replace('0', 'O').replace('1', 'l') + " ").code)
    }

    @Test
    fun readsTheCardsQrCode() {
        val claim = ClaimCodes.parse("knura://claim?uid=04DE5F1EACC040&code=$plain")
        assertEquals(ClaimCodes.Claim(plain, "04DE5F1EACC040"), claim)
        assertNull(ClaimCodes.parse("knura://claim?uid=nope&code=$plain").uid)
    }

    @Test
    fun catchesTypos() {
        val typo = code.substring(0, 5) + (if (code[5] == '2') '3' else '2') + code.substring(6)
        val e = assertThrows(IllegalArgumentException::class.java) { ClaimCodes.parse(typo) }
        assertEquals("That claim code doesn't check out. Look for a typo.", e.message)
        for (bad in listOf("", code.substring(0, 20), code.replace('G', 'U'), "knura://claim?uid=04DE5F1EACC040",
            "knura://pair?code=$plain", "https://knura.io/new")) {
            assertThrows(bad, IllegalArgumentException::class.java) { ClaimCodes.parse(bad) }
        }
    }
}
