package io.knura.android

import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetTextTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-31T15:00:00Z")

    private fun snap(taps: String) = Snapshot.of(Instant.parse("2026-10-31T14:59:00Z"), null, null,
        JSONObject("""{"taps":[$taps]}"""), null)

    @Test
    fun lastTapNamesTheTagAndSaysWhen() {
        val s = snap("""{"time":"2026-10-31T14:58:00Z","status":"ok","uid":"04356B02936980","counter":4,"actions":1},""" +
            """{"time":"2026-10-31T14:00:00Z","status":"rejected","uid":"04AABBCCDDEEFF","actions":0}""")
        assertEquals(WidgetText.LastTap("Front door", "Verified · 2 min ago", true),
            WidgetText.lastTap(s, { if (it == "04356B02936980") "Front door" else null }, utc, now))
        assertEquals(WidgetText.LastTap("04AABB…EEFF", "Rejected · yesterday", false),
            WidgetText.lastTap(snap("""{"time":"2026-10-30T14:00:00Z","status":"rejected","uid":"04AABBCCDDEEFF","actions":0}"""), { null }, utc, now))
        assertEquals("Open Knura to load your taps", WidgetText.lastTap(null, { null }, utc, now).detail)
        assertEquals("Checked 1 min ago", WidgetText.lastTap(snap(""), { null }, utc, now).detail)
    }

    @Test
    fun todayCountsRejectedToo() {
        val s = snap("""{"time":"2026-10-31T14:58:00Z","status":"ok","uid":"04356B02936980","actions":1},""" +
            """{"time":"2026-10-31T09:00:00Z","status":"rejected","uid":"04AABBCCDDEEFF","actions":0},""" +
            """{"time":"2026-10-30T09:00:00Z","status":"ok","uid":"04AABBCCDDEEFF","actions":0}""")
        assertEquals(WidgetText.Today("2", "taps · 1 rejected"), WidgetText.today(s, utc, now))
        assertEquals(WidgetText.Today("1", "tap"),
            WidgetText.today(snap("""{"time":"2026-10-31T10:00:00Z","status":"ok","uid":"04356B02936980","actions":1}"""), utc, now))
        assertEquals(WidgetText.Today("0", "taps"), WidgetText.today(snap(""), utc, now))
    }
}
