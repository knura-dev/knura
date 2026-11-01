package io.knura.android

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-31T15:00:00Z")

    private fun sample() = Snapshot.of(
        now,
        JSONObject("""{"status":"up","uptimeSeconds":3700,"keys":"master","publicUrl":"https://desk.example","resolverOwner":null,"knots":2,"tags":2}"""),
        JSONObject("""{"tags":[{"uid":"04356B02936980","lastCounter":12},{"uid":"04AABBCCDDEEFF","lastCounter":3}]}"""),
        JSONObject("""{"taps":[
            {"time":"2026-10-31T14:58:00Z","status":"ok","uid":"04356B02936980","counter":12,"actions":2},
            {"time":"2026-10-31T09:00:00Z","status":"rejected","uid":"04AABBCCDDEEFF","actions":0},
            {"time":"2026-10-30T20:00:00Z","status":"ok","uid":"04356B02936980","counter":11,"actions":1},
            {"time":"2026-10-25T08:00:00Z","status":"replay","uid":null,"actions":0},
            {"time":"2026-10-20T08:00:00Z","status":"ok","uid":"04356B02936980","counter":5,"actions":1},
            {"time":"not a time","status":"ok"}
        ]}"""),
        JSONObject("""{"tokens":["laptop","fold7"],"you":"fold7"}"""),
    )

    @Test
    fun countsTapsByDay() {
        val s = sample()
        assertEquals(5, s.taps.size)
        assertEquals(2, s.tapsToday(utc, now))
        assertArrayEquals(intArrayOf(1, 0, 0, 0, 0, 1, 2), s.lastSevenDays(utc, now))
    }

    @Test
    fun findsTheNewestTapOfATag() {
        val s = sample()
        assertEquals(12, s.lastTap("04356B02936980")?.counter)
        assertEquals("rejected", s.lastTap("04AABBCCDDEEFF")?.status)
        assertNull(s.lastTap("04000000000000"))
    }

    @Test
    fun survivesBeingSavedAndRead() {
        val s = sample()
        assertEquals(s, Snapshot.fromJson(s.toJson().toString()))
        assertEquals("fold7", s.you)
        assertEquals(listOf("laptop", "fold7"), s.tokens)
        assertEquals("https://desk.example", s.status?.publicUrl)
        assertNull(s.status?.resolverOwner)
    }

    @Test
    fun readsNothingFromNothing() {
        assertNull(Snapshot.fromJson(null))
        assertNull(Snapshot.fromJson("not json"))
        val empty = Snapshot.of(now, null, null, null, null)
        assertEquals(0, empty.tags.size)
        assertNull(empty.tokens)
        assertEquals(empty, Snapshot.fromJson(empty.toJson().toString()))
    }

    @Test
    fun greetsByTheClock() {
        assertEquals("Good morning", Words.greeting(0))
        assertEquals("Good morning", Words.greeting(11))
        assertEquals("Good afternoon", Words.greeting(12))
        assertEquals("Good afternoon", Words.greeting(16))
        assertEquals("Good evening", Words.greeting(17))
        assertEquals("Good evening", Words.greeting(23))
    }

    @Test
    fun saysHowLongAgo() {
        assertEquals("Just now", Words.ago(now.minusSeconds(30), utc, now))
        assertEquals("4 min ago", Words.ago(now.minusSeconds(240), utc, now))
        assertEquals("3 h ago", Words.ago(now.minusSeconds(3 * 3600), utc, now))
        assertEquals("Yesterday", Words.ago(Instant.parse("2026-10-30T23:00:00Z"), utc, now))
        assertEquals("Oct 22", Words.ago(Instant.parse("2026-10-22T10:00:00Z"), utc, now))
        assertEquals("2:58 PM", Words.clock(Instant.parse("2026-10-31T14:58:00Z"), utc, now))
    }

    @Test
    fun shortensAndCounts() {
        assertEquals("04356B…6980", Words.shortUid("04356B02936980"))
        assertEquals("1d 4h", Words.uptime(86400 + 4 * 3600 + 59))
        assertEquals("1h 1m", Words.uptime(3700))
        assertEquals("Tap #12 · 2 actions ran", Words.tapDetail(sample().taps[0]))
        assertEquals("Signature did not match", Words.tapDetail(sample().taps[1]))
    }

    @Test
    fun readsTheBackupDate() {
        val s = Snapshot.of(now, JSONObject("""{"uptimeSeconds":1,"keys":"master","publicUrl":null,"resolverOwner":null,"knots":0,"tags":0,""" +
            """"backup":{"file":"2026-10-30T12:00:00Z","sheet":null}}"""), null, null, null)
        assertEquals("2026-10-30", s.status?.backup?.latestDay())
        assertEquals(s, Snapshot.fromJson(s.toJson().toString()))
        assertNull(sample().status?.backup)
    }
}
