package com.v2ray.ang.core

/** User intent is independent of the current working profile chosen by the daemon. */
data class MostRouteSelection(
    val enabled: Boolean = false,
    val country: String = "",
    val groupId: String = "",
    val preferredGuid: String = "",
    val requestId: String = "",
)

data class MostRouteCandidate(
    val guid: String,
    val country: String,
    val groupId: String,
    val network: String,
    val bridge: Boolean,
)

/** Pure metadata policy. Measured exit-country checks remain in the watchdog. */
object MostRoutePolicy {
    val countries = listOf("nl", "de", "pl", "fi", "msk")
    private val bridge = Regex("(?:bridge[-_ ]|(?:msk|мск|москва)\\s*(?:→|->|[-_])\\s*)(nl|de|pl|fi)(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
    fun isBridge(label: String) = bridge.containsMatchIn(label)
    fun country(label: String, server: String?): String {
        bridge.find(label)?.let { return it.groupValues[1].lowercase(java.util.Locale.ROOT) }
        val names = listOf(
            "nl" to "🇳🇱|нидерланд|голланд|netherlands|\\bnl\\b",
            "de" to "🇩🇪|германи|germany|\\bde\\b",
            "pl" to "🇵🇱|польш|poland|\\bpl\\b",
            "fi" to "🇫🇮|финлянд|finland|\\bfi\\b",
            "msk" to "🇷🇺|москва|россия|russia|\\bmsk\\b",
        )
        names.firstOrNull { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(label) }?.let { return it.first }
        val domain = server.orEmpty().lowercase(java.util.Locale.ROOT)
        return countries.firstOrNull { Regex("(?:^|\\.)${it}\\d*(?:\\.|$)").containsMatchIn(domain) }.orEmpty()
    }

    fun eligible(selection: MostRouteSelection, candidates: List<MostRouteCandidate>): List<MostRouteCandidate> =
        if (!selection.enabled || selection.country !in countries) emptyList() else candidates.filter { it.country == selection.country && (selection.groupId.isBlank() || it.groupId == selection.groupId) }
            .distinctBy { it.guid }
            .sortedWith(compareBy<MostRouteCandidate> { it.bridge }.thenBy {
                when (it.network.lowercase(java.util.Locale.ROOT)) { "tcp", "raw", "" -> 0; "xhttp", "splithttp" -> 1; "grpc" -> 2; else -> 3 }
            }.thenBy { it.guid })

    fun choose(selection: MostRouteSelection, candidates: List<MostRouteCandidate>, tried: Set<String> = emptySet(), preferred: String = ""): String? {
        val eligible = eligible(selection, candidates).filterNot { it.guid in tried }
        return eligible.firstOrNull { it.guid == preferred }?.guid ?: eligible.firstOrNull()?.guid
    }

    fun requestedGuid(selection: MostRouteSelection, appliedRequest: String, current: String): String =
        if (selection.requestId != appliedRequest && selection.preferredGuid.isNotBlank()) selection.preferredGuid else current

    fun exitMatches(country: String, measured: String): Boolean =
        measured.isBlank() || measured.equals(if (country == "msk") "RU" else country, ignoreCase = true)
}
