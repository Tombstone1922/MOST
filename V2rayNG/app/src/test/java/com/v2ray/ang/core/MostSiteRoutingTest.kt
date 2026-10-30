package com.v2ray.ang.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MostSiteRoutingTest {
    private fun config() = JsonParser.parseString("""{
        "inbounds":[{"tag":"tun","protocol":"tun"},{"tag":"most-internal-health","protocol":"http"}],
        "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"vpn.example.net"}},{"tag":"direct","protocol":"freedom"}],
        "routing":{"rules":[{"network":"tcp,udp","outboundTag":"proxy"}]}
    }""").asJsonObject
    @Test fun domainsNormalizeWithoutAcceptingAddressesOrXrayExpressions() {
        assertEquals(listOf("example.ru", "xn--e1afmkfd.xn--p1ai"), MostSiteRouting.parse("HTTPS://Example.RU/account\n*.example.ru\nпример.рф\n"))
        listOf("127.0.0.1", "localhost", "domain:ru", "regexp:.*", "a..ru", "https://user:pass@example.ru", "https://[::1]", "ftp://example.ru", "invalid[.ru").forEach {
            assertThrows(IllegalArgumentException::class.java) { MostSiteRouting.parse(it) }
        }
        assertEquals(emptyList<String>(), MostSiteRouting.parse("\n  \n"))
        assertThrows(IllegalArgumentException::class.java) { MostSiteRouting.parse((0..256).joinToString("\n") { "a$it.example.ru" }) }
    }
    @Test fun directZonesCannotOverrideForcedVpnAndDoNotMatchLookalikeSuffixes() {
        val json = config()
        MostSiteConfig.apply(json, MostSiteRouting(ruDirect = true, rfDirect = true, suDirect = true, directDomains = listOf("bank.example"), proxyDomains = listOf("keep.ru")))
        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules")
        fun route(host: String): String {
            return rules.firstOrNull { entry ->
                val rule = entry.asJsonObject
                val domains = rule.getAsJsonArray("domain") ?: return@firstOrNull false
                domains.any { d -> val value = d.asString.removePrefix("domain:"); host == value || host.endsWith(".$value") }
            }?.asJsonObject?.get("outboundTag")?.asString ?: "proxy"
        }
        assertEquals("direct", route("example.ru")); assertEquals("direct", route("sub.example.ru"))
        assertEquals("proxy", route("keep.ru")); assertEquals("proxy", route("login.keep.ru"))
        assertEquals("proxy", route("example.ru.evil.com")); assertEquals("proxy", route("notru.com"))
        assertEquals("direct", route("bank.example")); assertEquals("direct", route("xn--e1afmkfd.xn--p1ai")); assertEquals("direct", route("example.su"))
        assertEquals("proxy", rules.last().asJsonObject.get("outboundTag").asString)
        assertFalse(json.getAsJsonArray("inbounds")[1].asJsonObject.has("sniffing"))
        assertTrue(json.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing").get("routeOnly").asBoolean)
        assertEquals("UseIP", json.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("domainStrategy").asString)
        assertTrue(json.getAsJsonObject("dns").getAsJsonArray("servers").any { it.isJsonObject && it.asJsonObject.get("tag")?.asString == "most-sites-bootstrap" })
    }
    @Test fun defaultAndPreviousSettingsDoNotChangeExistingRouting() {
        val old = Gson().fromJson("{}", MostSiteRouting::class.java)
        assertFalse(old.active)
        val json = config(); val before = json.toString(); MostSiteConfig.apply(json, old)
        assertEquals(before, json.toString())
        val fake = config(); fake.getAsJsonArray("inbounds")[0].asJsonObject.add("sniffing", JsonParser.parseString("""{"destOverride":["fakedns"],"enabled":true}"""))
        MostSiteConfig.apply(fake, MostSiteRouting(ruDirect = true))
        assertFalse(fake.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing").get("routeOnly").asBoolean)
    }
    @Test fun policyGroupRulesKeepTheirBalancer() {
        val json = config(); json.getAsJsonObject("routing").add("balancers", JsonParser.parseString("""[{"tag":"balancer-main"}]"""))
        MostSiteConfig.apply(json, MostSiteRouting(ruDirect = true, proxyDomains = listOf("keep.ru")))
        val force = json.getAsJsonObject("routing").getAsJsonArray("rules").first { it.asJsonObject.getAsJsonArray("domain")?.any { d -> d.asString == "domain:keep.ru" } == true }.asJsonObject
        assertEquals("balancer-main", force.get("balancerTag").asString); assertFalse(force.has("outboundTag"))
    }
}
