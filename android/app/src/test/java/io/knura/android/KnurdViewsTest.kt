package io.knura.android

import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class KnurdViewsTest {
    @Test
    fun statusReadsLikeKnurctl() {
        val rows = KnurdViews.status(JSONObject(
            """{"status":"up","uptimeSeconds":90061,"keys":"master","publicUrl":"https://desk.tail1234.ts.net",""" +
                """"resolverOwner":null,"knots":3,"tags":1}""")).rows
        assertEquals(KnurdViews.Row("Up for", "1d 1h 1m"), rows[0])
        assertEquals(KnurdViews.Row("Public URL", "https://desk.tail1234.ts.net", mono = true), rows[2])
        assertEquals(KnurdViews.Row("Resolver", "Not registered"), rows[3])
        assertEquals(KnurdViews.Row("Knots", "3 with actions"), rows[4])
    }

    @Test
    fun statusSaysWhenTheKeyWasBackedUp() {
        val base = """{"status":"up","uptimeSeconds":1,"keys":"master","publicUrl":null,"resolverOwner":null,"knots":0,"tags":0"""
        val never = KnurdViews.status(JSONObject(base + ""","backup":{"file":null,"sheet":null}}""")).rows
        assertEquals(KnurdViews.Row("Key backup", "Not backed up yet (knurctl backup create)"), never[4])
        val done = KnurdViews.status(JSONObject(base + ""","backup":{"file":"2026-10-30T12:00:00Z","sheet":"2026-11-01T09:00:00Z"}}""")).rows
        assertEquals(KnurdViews.Row("Key backup", "Backed up 2026-11-01"), done[4])
        assertEquals("older knurd: no backup row", 6, KnurdViews.status(JSONObject("$base}")).rows.size)
    }

    @Test
    fun tags() {
        assertEquals(listOf(KnurdViews.Row("04DE5F1EACC040", "Last counter 61", mono = true)),
            KnurdViews.tags(JSONObject("""{"tags":[{"uid":"04DE5F1EACC040","lastCounter":61}]}""")).rows)
        val none = KnurdViews.tags(JSONObject("""{"tags":[]}"""))
        assertEquals(emptyList<KnurdViews.Row>(), none.rows)
        assertEquals("knurd hasn't accepted a tap from any tag yet.", none.empty)
    }

    @Test
    fun tapsInThePhonesTime() {
        val taps = KnurdViews.taps(JSONObject("""{"taps":[""" +
            """{"time":"2026-10-22T02:20:58Z","status":"ok","uid":"04DE5F1EACC040","counter":61,"actions":1},""" +
            """{"time":"2026-10-22T02:19:00Z","status":"malformed","uid":null,"actions":0}]}"""), ZoneOffset.ofHours(2))
        assertEquals(listOf(
            KnurdViews.Row("04DE5F1EACC040", "10-22 04:20:58 · #61 · 1 action", "Genuine", KnurdViews.Badge.GOOD, mono = true),
            KnurdViews.Row("No UID", "10-22 04:19:00 · no actions", "Malformed", KnurdViews.Badge.BAD),
        ), taps.rows)
    }

    @Test
    fun tokenNames() {
        assertEquals(listOf("fold7", "laptop"),
            KnurdViews.tokenNames(JSONObject("""{"you":"fold7","tokens":["fold7","laptop"]}""")))
        assertEquals(emptyList<String>(), KnurdViews.tokenNames(JSONObject("{}")))
    }
}
