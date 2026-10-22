package io.knura.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnurdViewsTest {
    @Test
    fun statusReadsLikeKnurctl() {
        val text = KnurdViews.status(JSONObject(
            """{"status":"up","uptimeSeconds":90061,"keys":"master","publicUrl":"https://desk.tail1234.ts.net",""" +
                """"resolverOwner":null,"knots":3,"tags":1}"""))
        assertTrue(text, text.startsWith("knurd is up (for 1d 1h 1m)"))
        assertTrue(text, text.contains("Public URL:  https://desk.tail1234.ts.net"))
        assertTrue(text, text.contains("Resolver:    not registered"))
        assertTrue(text, text.contains("Knots:       3 with actions"))
    }

    @Test
    fun tagsAndTaps() {
        assertEquals("UID              LAST COUNTER\n04DE5F1EACC040   61",
            KnurdViews.tags(JSONObject("""{"tags":[{"uid":"04DE5F1EACC040","lastCounter":61}]}""")))
        assertEquals("knurd hasn't accepted a tap from any tag yet.", KnurdViews.tags(JSONObject("""{"tags":[]}""")))
        assertEquals("Newest first, times in UTC:\n" +
            "10-22 02:20:58  ok  04DE5F1EACC040 #61  actions 1\n" +
            "10-22 02:19:00  malformed  -  actions 0",
            KnurdViews.taps(JSONObject("""{"taps":[""" +
                """{"time":"2026-10-22T02:20:58Z","status":"ok","uid":"04DE5F1EACC040","counter":61,"actions":1},""" +
                """{"time":"2026-10-22T02:19:00Z","status":"malformed","uid":null,"actions":0}]}""")))
    }

    @Test
    fun tokenNames() {
        assertEquals(listOf("fold7", "laptop"),
            KnurdViews.tokenNames(JSONObject("""{"you":"fold7","tokens":["fold7","laptop"]}""")))
        assertEquals(emptyList<String>(), KnurdViews.tokenNames(JSONObject("{}")))
    }
}
