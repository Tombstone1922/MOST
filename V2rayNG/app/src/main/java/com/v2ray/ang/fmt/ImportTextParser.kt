package com.v2ray.ang.fmt

import java.net.URI
import java.util.Locale

enum class ImportTextIssue { EMPTY, TOO_LARGE, UNSUPPORTED, INVALID, MIXED, MULTIPLE_SUBSCRIPTIONS }

data class ImportTextPreview(
    val profiles: List<String> = emptyList(),
    val subscription: String? = null,
    val duplicates: Int = 0,
    val issue: ImportTextIssue? = null,
) {
    val ready: Boolean get() = issue == null && (profiles.isNotEmpty() || subscription != null)
    val needsHttpConsent: Boolean get() = subscription?.startsWith("http://") == true
}

/** Clipboard syntax belongs to the format layer; this parser never reads storage or the network. */
object ImportTextParser {
    private val schemes = Regex("(?i)(?<![a-z0-9_=/])(?:vless|https?)://")
    private val invisible = Regex("[\\u200B\\u200E\\u200F\\u2060\\uFEFF]")
    private const val MAX_LENGTH = 262144

    fun parse(input: String): ImportTextPreview {
        if (input.length > MAX_LENGTH) return ImportTextPreview(issue = ImportTextIssue.TOO_LARGE)
        val text = input.replace(invisible, "").trim()
        if (text.isEmpty()) return ImportTextPreview(issue = ImportTextIssue.EMPTY)
        val matches = schemes.findAll(text).toList()
        if (matches.isEmpty()) return ImportTextPreview(issue = ImportTextIssue.UNSUPPORTED)
        val profiles = linkedMapOf<String, String>()
        val subscriptions = linkedSetOf<String>()
        var duplicates = 0
        for ((index, match) in matches.withIndex()) {
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            var candidate = text.substring(match.range.first, end)
                .takeWhile { it != '\n' && it != '\r' && it != '<' && it != '>' && it != '`' && it != '"' }
                .trim().trimEnd(')', ']', ',', ';', '\'').trim()
            // Markdown's closing parenthesis is a delimiter, not part of the connection.
            if (match.range.first >= 2 && text.substring(match.range.first - 2, match.range.first) == "](") {
                candidate = candidate.substringBefore(')')
            }
            val colon = candidate.indexOf(':')
            candidate = candidate.substring(0, colon).lowercase(Locale.ROOT) + candidate.substring(colon)
            val fragment = candidate.indexOf('#')
            if (fragment >= 0) {
                candidate = candidate.substring(0, fragment) + candidate.substring(fragment).replace(" ", "%20")
            }
            val uri = try { URI(candidate) } catch (_: Exception) {
                return ImportTextPreview(issue = ImportTextIssue.INVALID)
            }
            if (uri.host.isNullOrBlank() || uri.port == 0 || uri.port > 65535 || uri.port < -1) {
                return ImportTextPreview(issue = ImportTextIssue.INVALID)
            }
            if (uri.scheme == "vless") {
                if (uri.userInfo.isNullOrBlank() || uri.port < 1) return ImportTextPreview(issue = ImportTextIssue.INVALID)
                val identity = listOf(uri.userInfo, uri.host.lowercase(Locale.ROOT), uri.port.toString(),
                    uri.rawPath.orEmpty(), uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.sorted().joinToString("&"))
                    .joinToString("\u0000")
                if (profiles.putIfAbsent(identity, candidate) != null) duplicates++
            } else {
                if (uri.userInfo != null) return ImportTextPreview(issue = ImportTextIssue.INVALID)
                if (!subscriptions.add(candidate)) duplicates++
            }
        }
        if (profiles.isNotEmpty() && subscriptions.isNotEmpty()) return ImportTextPreview(issue = ImportTextIssue.MIXED)
        if (subscriptions.size > 1) return ImportTextPreview(issue = ImportTextIssue.MULTIPLE_SUBSCRIPTIONS)
        return ImportTextPreview(profiles.values.toList(), subscriptions.firstOrNull(), duplicates)
    }
}
