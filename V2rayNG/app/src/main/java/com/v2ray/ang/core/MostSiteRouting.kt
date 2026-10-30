package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import java.net.IDN
import java.net.URI
import java.util.Locale

/** App-owned site preferences; a separate owner from profile/provider routing rules. */
data class MostSiteRouting(
    val ruDirect: Boolean = false,
    val rfDirect: Boolean = false,
    val suDirect: Boolean = false,
    val directDomains: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(),
) {
    val active: Boolean get() = ruDirect || rfDirect || suDirect || directDomains.isNotEmpty() || proxyDomains.isNotEmpty()
    val directRules: List<String> get() = directDomains.map { "domain:$it" } +
        (if (ruDirect) listOf("domain:ru") else emptyList()) +
        (if (rfDirect) listOf("domain:xn--p1ai") else emptyList()) +
        (if (suDirect) listOf("domain:su") else emptyList())

    companion object {
        fun normalize(input: String): String {
            var value = input.trim().removePrefix("*.")
            require(value.isNotEmpty() && value.none(Char::isWhitespace))
            if (!value.contains("://")) value = "https://$value"
            val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid domain") }
            require(uri.scheme.lowercase(Locale.ROOT) in listOf("http", "https") && uri.rawUserInfo == null)
            // URI.host is null for Unicode hostnames; parse the authority without accepting credentials or IPv6.
            val authority = uri.rawAuthority ?: throw IllegalArgumentException("Missing domain")
            require(!authority.contains('@') && !authority.contains('[') && !authority.contains(']'))
            val parts = authority.split(':')
            require(parts.size <= 2 && (parts.size == 1 || parts[1].toIntOrNull()?.let { it in 1..65535 } == true))
            val domain = IDN.toASCII(parts[0].trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(domain.length <= 253 && domain.contains('.') && !domain.matches(Regex("[0-9.]+")))
            require(domain.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) })
            return domain
        }
        fun parse(text: String): List<String> {
            require(text.length <= 65536)
            val domains = text.lineSequence().map(String::trim).filter(String::isNotEmpty).map(::normalize).distinct().toList()
            require(domains.size <= 256)
            return domains
        }
    }
}

/** Pure JSON transform covers ordinary and custom Xray profiles without changing saved provider configs. */
object MostSiteConfig {
    private fun strings(values: Iterable<String>) = JsonArray().apply { values.forEach { add(it) } }
    fun apply(config: JsonObject, policy: MostSiteRouting) {
        if (!policy.active) return
        val outbounds = config.getAsJsonArray("outbounds") ?: error("Site routing requires Xray outbounds")
        val routing = config.getAsJsonObject("routing") ?: JsonObject().also { config.add("routing", it) }
        val balancer = routing.getAsJsonArray("balancers")?.any { it.asJsonObject.get("tag")?.asString == AppConfig.TAG_BALANCER } == true
        val hasProxy = outbounds.any { it.asJsonObject.get("tag")?.asString == "proxy" && it.asJsonObject.get("protocol")?.asString !in listOf("freedom", "blackhole", "dns") }
        check(hasProxy || balancer) { "Site routing requires a proxy outbound" }
        fun unique(base: String): String {
            val used = outbounds.mapNotNull { it.asJsonObject.get("tag")?.asString }.toSet()
            var tag = base; var suffix = 1
            while (tag in used) tag = "$base-${suffix++}"
            return tag
        }
        val direct = outbounds.firstOrNull { it.asJsonObject.get("tag")?.asString == "direct" && it.asJsonObject.get("protocol")?.asString == "freedom" }
            ?.asJsonObject?.get("tag")?.asString ?: unique("most-sites-direct").also { tag ->
            outbounds.add(JsonObject().apply { addProperty("tag", tag); addProperty("protocol", "freedom") })
        }
        val tags = mutableListOf<String>()
        config.getAsJsonArray("inbounds")?.forEachIndexed { index, entry ->
            val inbound = entry.asJsonObject
            if (inbound.get("tag")?.asString == "most-internal-health" || inbound.get("protocol")?.asString !in listOf("tun", "socks", "http", "tunnel", "dokodemo-door")) return@forEachIndexed
            val tag = inbound.get("tag")?.asString?.takeIf(String::isNotBlank) ?: "most-sites-in-$index".also { inbound.addProperty("tag", it) }
            tags.add(tag)
            val sniff = inbound.getAsJsonObject("sniffing") ?: JsonObject().also { inbound.add("sniffing", it) }
            val protocols = (sniff.getAsJsonArray("destOverride")?.map { it.asString }.orEmpty() + listOf("http", "tls", "quic")).distinct()
            sniff.addProperty("enabled", true); sniff.addProperty("metadataOnly", false)
            // Existing FakeDNS must still restore fake IPs to hostnames; real-IP traffic uses route-only sniffing.
            sniff.addProperty("routeOnly", "fakedns" !in protocols && "fakedns+others" !in protocols)
            sniff.add("destOverride", strings(protocols))
        }
        check(tags.isNotEmpty()) { "Site routing requires a supported local inbound" }
        val dns = config.getAsJsonObject("dns") ?: JsonObject().also { config.add("dns", it) }
        if (dns.getAsJsonArray("servers")?.size() == null || dns.getAsJsonArray("servers").size() == 0) dns.add("servers", strings(listOf("1.1.1.1", "8.8.8.8")))
        val dnsTag = dns.get("tag")?.asString?.takeIf(String::isNotBlank) ?: "most-sites-dns".also { dns.addProperty("tag", it) }
        val dnsOut = unique("most-sites-dns-out")
        outbounds.add(JsonObject().apply { addProperty("tag", dnsOut); addProperty("protocol", "dns") })
        val prefix = JsonArray()
        // Resolve VPN endpoint names outside the tunnel to avoid proxy-DNS bootstrap recursion.
        val endpointDomains = mutableListOf<String>()
        outbounds.forEach { entry ->
            val outbound = entry.asJsonObject
            if (outbound.get("protocol")?.asString in listOf("freedom", "blackhole", "dns")) return@forEach
            val settings = outbound.getAsJsonObject("settings") ?: return@forEach
            val hosts = listOfNotNull(settings.get("address")?.takeIf { it.isJsonPrimitive }?.asString) +
                settings.getAsJsonArray("vnext")?.mapNotNull { it.asJsonObject.get("address")?.asString }.orEmpty() +
                settings.getAsJsonArray("servers")?.mapNotNull { it.asJsonObject.get("address")?.asString }.orEmpty()
            val domains = hosts.filter { it.contains('.') && !it.matches(Regex("[0-9.]+")) && !it.contains(':') }
            if (domains.isNotEmpty()) {
                endpointDomains.addAll(domains)
                val stream = outbound.getAsJsonObject("streamSettings") ?: JsonObject().also { outbound.add("streamSettings", it) }
                val sockopt = stream.getAsJsonObject("sockopt") ?: JsonObject().also { stream.add("sockopt", it) }
                sockopt.addProperty("domainStrategy", "UseIP")
            }
        }
        if (endpointDomains.isNotEmpty()) {
            val bootstrap = unique("most-sites-bootstrap")
            val servers = JsonArray()
            listOf("1.1.1.1", "8.8.8.8").forEach { address -> servers.add(JsonObject().apply {
                addProperty("address", address); addProperty("tag", bootstrap); addProperty("skipFallback", true)
                add("domains", strings(endpointDomains.distinct().map { "full:$it" }))
            }) }
            dns.getAsJsonArray("servers").forEach { servers.add(it) }; dns.add("servers", servers)
            prefix.add(JsonObject().apply { addProperty("type", "field"); add("inboundTag", strings(listOf(bootstrap))); addProperty("outboundTag", direct) })
        }
        fun proxyTarget(rule: JsonObject): JsonObject = rule.apply { if (balancer) addProperty("balancerTag", AppConfig.TAG_BALANCER) else addProperty("outboundTag", "proxy") }
        fun rule(domains: List<String>, target: String?) = JsonObject().apply {
            addProperty("type", "field"); add("inboundTag", strings(tags)); add("domain", strings(domains))
            if (target == null) proxyTarget(this) else addProperty("outboundTag", target)
        }
        prefix.add(JsonObject().apply { addProperty("type", "field"); add("inboundTag", strings(tags)); addProperty("port", "53"); addProperty("outboundTag", dnsOut) })
        prefix.add(proxyTarget(JsonObject().apply { addProperty("type", "field"); add("inboundTag", strings(listOf(dnsTag))) }))
        if (policy.proxyDomains.isNotEmpty()) prefix.add(rule(policy.proxyDomains.map { "domain:$it" }, null))
        if (policy.directRules.isNotEmpty()) prefix.add(rule(policy.directRules, direct))
        routing.getAsJsonArray("rules")?.forEach { prefix.add(it) }
        routing.add("rules", prefix)
    }
}
