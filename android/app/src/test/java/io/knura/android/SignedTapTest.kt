package io.knura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignedTapTest {
    private val query = "e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086"
    private val route = SignedTap.Route("pixel", "https://desk.tail1234.ts.net/tap", "abcdefghjk")

    @Test
    fun ownResolverLinksGoToTheServer() {
        assertEquals(SignedTap.Target("https://desk.tail1234.ts.net/tap", query),
            SignedTap.target("https://tap.knura.io/abcdefghjk?$query", route))
    }

    @Test
    fun ownDirectLinksGoToTheServer() {
        assertEquals(SignedTap.Target("https://desk.tail1234.ts.net/tap", query),
            SignedTap.target("https://desk.tail1234.ts.net/tap?$query", route))
    }

    @Test
    fun otherPeoplesTagsAreLeftToTheBrowser() {
        assertNull(SignedTap.target("https://tap.knura.io/zzzzzzzzzz?$query", route))
        assertNull(SignedTap.target("https://example.com/tap?$query", route))
        assertNull(SignedTap.target("http://desk.tail1234.ts.net/tap?$query", route))
        assertNull(SignedTap.target("https://tap.knura.io/abcdefghjk", route))
        assertNull(SignedTap.target("not a link", route))
        assertNull(SignedTap.target("https://tap.knura.io/abcdefghjk?$query", route.copy(tapUrl = null)))
        assertNull(SignedTap.target("https://tap.knura.io/abcdefghjk?$query", route.copy(owner = null)))
    }

    @Test
    fun theMessageMatchesTheServers() {
        assertEquals("knura-tap-v1\n$query\npixel\n1800000000\neveryday",
            String(SignedTap.message(query, "pixel", 1_800_000_000L, SignedTap.EVERYDAY), Charsets.UTF_8))
    }

    @Test
    fun routesReadBackFromJson() {
        assertEquals(route, SignedTap.Route.fromJson(route.toJson()))
        val noTunnel = SignedTap.Route("pixel", null, null)
        assertEquals(noTunnel, SignedTap.Route.fromJson(noTunnel.toJson()))
    }

    @Test
    fun answersBecomeResults() {
        val ok = org.json.JSONObject("{\"status\":\"ok\",\"uid\":\"04DE5F1EACC040\",\"counter\":61,\"actions\":2," +
            "\"tapper\":{\"phone\":\"pixel\",\"confirmed\":false}}")
        val done = SignedTap.describe(200, ok)
        assertEquals(true, done.good)
        assertEquals("Tap verified. Started 2 actions.", done.subtitle)
        assertEquals(listOf("Tag" to "04DE5F1EACC040", "Tap number" to "61", "Signed by" to "pixel"), done.rows)
        assertEquals("Already used", SignedTap.describe(409, org.json.JSONObject()).title)
        assertEquals("the signature doesn't match",
            SignedTap.describe(401, org.json.JSONObject("{\"detail\":\"the signature doesn't match\"}")).subtitle)
    }
}
