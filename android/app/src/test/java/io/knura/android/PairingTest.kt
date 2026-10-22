package io.knura.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingTest {
    private val fp = "ab".repeat(32)
    private val token = "knurd_" + "A".repeat(43)

    @Test
    fun readsWhatKnurctlWrites() {
        val p = Pairing.parse("knura://pair?url=https%3A%2F%2F192.168.1.20%3A8425&fp=$fp&token=$token")
        assertEquals(Pairing("https://192.168.1.20:8425", fp, token), p)
    }

    @Test
    fun refusesAnythingElse() {
        for (bad in listOf(
            "",
            "https://example.com",
            "knura://pair?url=http%3A%2F%2F192.168.1.20%3A8425&fp=$fp&token=$token",
            "knura://pair?url=https%3A%2F%2F192.168.1.20%3A8425%2Fx&fp=$fp&token=$token",
            "knura://pair?url=https%3A%2F%2F192.168.1.20%3A8425&fp=abc&token=$token",
            "knura://pair?url=https%3A%2F%2F192.168.1.20%3A8425&fp=$fp&token=nope",
            "knura://other?url=https%3A%2F%2F192.168.1.20%3A8425&fp=$fp&token=$token",
        )) {
            assertThrows(bad, IllegalArgumentException::class.java) { Pairing.parse(bad) }
        }
    }

    @Test
    fun hexRoundTrips() {
        val bytes = byteArrayOf(0x90.toByte(), 0x00, 0x0A, 0xFF.toByte())
        assertEquals("90000AFF", bytes.toHex())
        assertArrayEquals(bytes, "90000AFF".hexToBytes())
        assertArrayEquals(bytes, "90000aff".hexToBytes())
    }

    @Test
    fun pinsBySha256OfTheCertificate() {
        // SHA-256 of the empty input, as knurctl's CertFingerprint writes it: lowercase hex.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PinnedTrustManager.sha256Hex(ByteArray(0)))
    }
}
