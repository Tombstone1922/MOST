package com.v2ray.ang.ui.main

import java.net.URI

/** Metadata labels only; country is not a measurement of the tunnel's exit IP. */
internal object MostPresentation {
    private val bridge = Regex("(?:bridge[-_ ]|(?:msk|мск|москва)\\s*(?:→|->|[-_])\\s*)(nl|de|pl|fi)(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
    fun isBridge(remarks: String): Boolean = bridge.containsMatchIn(remarks)
    fun country(remarks: String, server: String?): String {
        bridge.find(remarks)?.let { return it.groupValues[1].lowercase() }
        val domain = server.orEmpty().lowercase()
        return listOf("nl", "de", "pl", "fi", "msk").firstOrNull {
            Regex("(?:^|\\.)${it}\\d*(?:\\.|$)").containsMatchIn(domain)
        }.orEmpty()
    }
    fun validSubscription(value: String, allowHttp: Boolean): Boolean = try {
        val uri = URI(value.trim())
        val scheme = uri.scheme.orEmpty().lowercase()
        (scheme == "https" || (scheme == "http" && allowHttp)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null &&
            (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }
    fun toggleFavorite(current: Set<String>, guid: String): Set<String> =
        if (guid in current) current - guid else current + guid

    fun subscriptionHost(address: String): String = try {
        URI(address.trim()).host.orEmpty()
    } catch (_: Exception) { "" }

    fun validSubscriptionName(value: String): Boolean =
        value.trim().length in 1..80 && value.none { it == '\n' || it == '\r' }

    /** Display labels only: persisted GUIDs and private subscription URLs stay unchanged. */
    fun subscriptionTitles(items: List<Triple<String, String, String>>, fallback: String, local: String): Map<String, String> {
        fun key(value: String) = value.trim().lowercase(java.util.Locale.ROOT)
        val generic = setOf("моя подписка", "my subscription", "subscription", key(fallback))
        val bases = items.associate { (id, name, address) ->
            id to if (address.isBlank()) local else {
                name.trim().takeUnless { it.isBlank() || key(it) in generic }
                    ?: subscriptionHost(address).ifBlank { fallback }
            }
        }
        val reserved = bases.values.map(::key).toSet()
        val used = mutableSetOf<String>()
        return items.associate { (id, _, _) ->
            val base = bases.getValue(id)
            var label = base
            var suffix = 2
            while (key(label) in used) {
                label = "$base · ${suffix++}"
                while (key(label) in reserved) label = "$base · ${suffix++}"
            }
            used += key(label)
            id to label
        }
    }

    fun importedGroup(address: String, groups: List<Pair<String, String>>, current: String): String {
        val url = address.trim()
        if (url.isEmpty()) return current
        return groups.firstOrNull { it.second == url }?.first ?: current
    }
}
