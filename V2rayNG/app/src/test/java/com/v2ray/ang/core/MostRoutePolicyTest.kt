package com.v2ray.ang.core

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MostRoutePolicyTest {
    private val nl = MostRouteSelection(true, "nl", "family")
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
        assertEquals(listOf("tcp", "xhttp", "grpc", "bridge"), MostRoutePolicy.eligible(nl, candidates).map { it.guid })
        assertEquals(emptyList<MostRouteCandidate>(), MostRoutePolicy.eligible(nl.copy(enabled = false), candidates))
        assertEquals(emptyList<MostRouteCandidate>(), MostRoutePolicy.eligible(nl.copy(country = ""), candidates))
        assertEquals(emptyList<MostRouteCandidate>(), MostRoutePolicy.eligible(nl.copy(groupId = "missing"), candidates))
    }

    @Test
    fun `failover exhausts same-country profiles without selecting another owner or country`() {
        val tried = linkedSetOf<String>()
        repeat(4) { tried += requireNotNull(MostRoutePolicy.choose(nl, candidates, tried)) }
        assertEquals(setOf("tcp", "xhttp", "grpc", "bridge"), tried)
        assertNull(MostRoutePolicy.choose(nl, candidates, tried))
        assertNull(MostRoutePolicy.choose(nl.copy(country = "fi"), candidates))
    }

    @Test
    fun `last working profile is preferred only while eligible and not already failed`() {
        assertEquals("bridge", MostRoutePolicy.choose(nl, candidates, preferred = "bridge"))
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
    }
}
