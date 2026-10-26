package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MostPresentationTest {
    @Test
    fun `https URLs and IPv6 are accepted`() {
        for (url in listOf("https://example.com/sub", "HTTPS://example.com/sub", "https://[::1]:8443/s")) {
            assertTrue(MostPresentation.validSubscription(url, false), url)
        }
    }
    @Test
    fun `http requires explicit opt in`() {
        assertFalse(MostPresentation.validSubscription("http://example.com/sub", false))
        assertTrue(MostPresentation.validSubscription("http://example.com/sub", true))
    }
    @Test
    fun `invalid and credential URLs fail`() {
        for (url in listOf("", "bad", "vless://abc@example.com", "https://user:pw@example.com/s", "https://x:0/s", "https://x:65536/s", "https:///sub", "https://a b/s")) {
            assertFalse(MostPresentation.validSubscription(url, true), url)
        }
    }
    @Test
    fun `bridge metadata takes precedence over ingress hostname`() {
        for ((label, country) in listOf("h1auto-bridge-nl-xhttp" to "nl", "MSK → DE" to "de", "Москва -> PL" to "pl", "MSK-FI" to "fi")) {
            assertTrue(MostPresentation.isBridge(label), label)
            assertEquals(country, MostPresentation.country(label, "entry.msk1.example.com"))
        }
    }
    @Test
    fun `direct and unknown endpoints are not bridges`() {
        assertFalse(MostPresentation.isBridge("[МСК-прямое] VLESS"))
        assertEquals("nl", MostPresentation.country("Direct", "a.nl11.example.com"))
        assertEquals("de", MostPresentation.country("Direct", "a.de13.example.com"))
        assertEquals("pl", MostPresentation.country("Direct", "a.pl5.example.com"))
        assertEquals("msk", MostPresentation.country("Direct", "a.msk1.example.com"))
        assertEquals("", MostPresentation.country("Direct", "example.com"))
        assertEquals("", MostPresentation.country("Direct", null))
    }
    @Test
    fun `favorites use immutable GUID identity`() {
        val original = setOf("server-a")
        val added = MostPresentation.toggleFavorite(original, "server-b")
        assertEquals(setOf("server-a"), original)
        assertEquals(setOf("server-a", "server-b"), added)
        assertEquals(setOf("server-b"), MostPresentation.toggleFavorite(added, "server-a"))
        assertEquals(emptySet<String>(), MostPresentation.toggleFavorite(original, "server-a"))
    }

    @Test
    fun `newly imported subscription becomes visible instead of previous default group`() {
        val groups = listOf("default" to "", "existing" to "https://example.com/old", "new" to "https://example.com/new")
        assertEquals("new", MostPresentation.importedGroup(" https://example.com/new ", groups, "default"))
        assertEquals("existing", MostPresentation.importedGroup("https://example.com/old", groups, "new"))
    }

    @Test
    fun `unresolved or empty import keeps current group`() {
        val groups = listOf("default" to "", "existing" to "https://example.com/old")
        assertEquals("existing", MostPresentation.importedGroup("", groups, "existing"))
        assertEquals("existing", MostPresentation.importedGroup("https://example.com/missing", groups, "existing"))
        assertEquals("existing", MostPresentation.importedGroup("https://example.com/old", emptyList(), "existing"))
    }
    @Test
    fun `generic subscription names become provider names and local profiles stay separate`() {
        val titles = MostPresentation.subscriptionTitles(listOf(
            Triple("local", "Моя подписка", ""),
            Triple("one", "моя подписка", "https://one.example/private-token"),
            Triple("two", "Моя подписка", "https://two.example/secret")), "Подписка", "Локальные профили")
        assertEquals("Локальные профили", titles["local"])
        assertEquals("one.example", titles["one"])
        assertEquals("two.example", titles["two"])
        assertFalse(titles.values.any { "private-token" in it || "secret" in it })
    }

    @Test
    fun `case-only duplicates and existing numbered names get unique labels`() {
        val titles = MostPresentation.subscriptionTitles(listOf(
            Triple("a", "Home", "https://a.example/s"),
            Triple("b", "home", "https://b.example/s"),
            Triple("c", "home · 2", "https://c.example/s")), "Subscription", "Local profiles")
        assertEquals(3, titles.values.map { it.lowercase() }.toSet().size)
        assertEquals("Home", titles["a"])
        assertEquals("home · 3", titles["b"])
        assertEquals("home · 2", titles["c"])
    }

    @Test
    fun `subscription names require a nonblank bounded single line`() {
        assertTrue(MostPresentation.validSubscriptionName(" Family "))
        assertFalse(MostPresentation.validSubscriptionName("   "))
        assertFalse(MostPresentation.validSubscriptionName("a".repeat(81)))
        assertFalse(MostPresentation.validSubscriptionName("a\nb"))
    }
}
