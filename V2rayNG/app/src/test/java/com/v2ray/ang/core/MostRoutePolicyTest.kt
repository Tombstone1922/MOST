package com.v2ray.ang.core

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MostRoutePolicyTest {
    private val nl = MostRouteSelection(true, "nl", "family", directOnly = true)
    private val candidates = listOf(
        MostRouteCandidate("bridge", "nl", "family", "tcp", true),
        MostRouteCandidate("grpc", "nl", "family", "grpc", false),
        MostRouteCandidate("tcp", "nl", "family", "tcp", false),
        MostRouteCandidate("xhttp", "nl", "family", "xhttp", false),
        MostRouteCandidate("foreign", "de", "family", "tcp", false),
        MostRouteCandidate("other-owner", "nl", "work", "tcp", false),
    )

    @Test
    fun `bridge labels override the Moscow entry hostname`() {
        assertEquals("nl", MostRoutePolicy.country("[МСК-прямое] bridge-nl-xhttp", "entry.msk1.example.com"))
        assertEquals("de", MostRoutePolicy.country("Москва → DE", "entry.msk1.example.com"))
        assertEquals("pl", MostRoutePolicy.country("🇵🇱 Польша", "entry.example.com"))
        assertEquals("fi", MostRoutePolicy.country("Direct", "node.fi6.example.com"))
        assertEquals("", MostRoutePolicy.country("Unknown", "example.com"))
    }

    @Test
    fun `automatic candidates stay inside the requested country and subscription`() {
        assertEquals(listOf("tcp", "xhttp", "grpc"), MostRoutePolicy.eligible(nl, candidates).map { it.guid })
        assertEquals(listOf("bridge"), MostRoutePolicy.eligible(nl.copy(directOnly = false), candidates).map { it.guid })
        assertEquals(emptyList<MostRouteCandidate>(), MostRoutePolicy.eligible(nl.copy(enabled = false), candidates))
        assertEquals(emptyList<MostRouteCandidate>(), MostRoutePolicy.eligible(nl.copy(country = ""), candidates))
        assertEquals(emptyList<MostRouteCandidate>(), MostRoutePolicy.eligible(nl.copy(groupId = "missing"), candidates))
    }

    @Test
    fun `failover exhausts same-country profiles without selecting another owner or country`() {
        val tried = linkedSetOf<String>()
        repeat(3) { tried += requireNotNull(MostRoutePolicy.choose(nl, candidates, tried)) }
        assertEquals(setOf("tcp", "xhttp", "grpc"), tried)
        assertNull(MostRoutePolicy.choose(nl, candidates, tried))
        assertNull(MostRoutePolicy.choose(nl.copy(country = "fi"), candidates))
    }

    @Test
    fun `last working profile is preferred only while eligible and not already failed`() {
        assertEquals("grpc", MostRoutePolicy.choose(nl, candidates, preferred = "grpc"))
        assertEquals("tcp", MostRoutePolicy.choose(nl, candidates, preferred = "bridge"))
        assertEquals("bridge", MostRoutePolicy.choose(nl.copy(directOnly = false), candidates, preferred = "tcp"))
        assertEquals("tcp", MostRoutePolicy.choose(nl, candidates, setOf("bridge"), "bridge"))
        assertEquals("tcp", MostRoutePolicy.choose(nl, candidates, preferred = "foreign"))
        assertEquals("tcp", MostRoutePolicy.choose(nl, candidates, preferred = "deleted-profile"))
    }

    @Test
    fun `a newer manual choice wins over a stale automatic recovery`() {
        val request = MostRouteSelection(false, preferredGuid = "manual", requestId = "new-request")
        assertEquals("manual", MostRoutePolicy.requestedGuid(request, "old-request", "stale-auto-target"))
        assertEquals("current", MostRoutePolicy.requestedGuid(request, "new-request", "current"))
        assertEquals("current", MostRoutePolicy.requestedGuid(MostRouteSelection(), "", "current"))
    }

    @Test
    fun `measured exit mismatch is detected and unknown geolocation is not invented`() {
        assertTrue(MostRoutePolicy.exitMatches("nl", "NL"))
        assertFalse(MostRoutePolicy.exitMatches("nl", "RU"))
        assertTrue(MostRoutePolicy.exitMatches("msk", "RU"))
        assertTrue(MostRoutePolicy.exitMatches("nl", ""))
    }

    @Test
    fun `default and persisted request records retain manual mode and stable identities`() {
        assertEquals(MostRouteSelection(), Gson().fromJson("{}", MostRouteSelection::class.java))
        val request = nl.copy(preferredGuid = "tcp", requestId = "request-id")
        assertEquals(request, Gson().fromJson(Gson().toJson(request), MostRouteSelection::class.java))
        val legacy = Gson().fromJson("""{"enabled":true,"country":"nl","groupId":"family","preferredGuid":"bridge","requestId":"old"}""", MostRouteSelection::class.java)
        assertFalse(legacy.directOnly)
        assertEquals("old", legacy.requestId)
        assertEquals("bridge", MostRoutePolicy.choose(legacy, candidates, preferred = legacy.preferredGuid))
    }

    @Test
    fun `country cards count only the requested connection type`() {
        assertEquals(mapOf("nl" to 1), MostRoutePolicy.countryCounts(candidates, directOnly = false))
        assertEquals(mapOf("nl" to 4, "de" to 1), MostRoutePolicy.countryCounts(candidates, directOnly = true))
        assertEquals(emptyMap<String, Int>(), MostRoutePolicy.countryCounts(emptyList(), directOnly = false))
    }

    @Test
    fun `bridge recovery cannot fall through to a direct connection`() {
        val bridge = nl.copy(directOnly = false)
        assertNull(MostRoutePolicy.choose(bridge, candidates, setOf("bridge")))
        assertEquals("bridge", MostRoutePolicy.choose(bridge, candidates, preferred = "tcp"))
    }
}
