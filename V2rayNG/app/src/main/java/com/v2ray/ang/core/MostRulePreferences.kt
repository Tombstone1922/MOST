package com.v2ray.ang.core

import java.security.MessageDigest

/** A value key, not a running-state flag. Only the daemon writes the applied key after startup. */
object MostRulePreferences {
    fun fingerprint(sites: String, enabled: Boolean, bypass: Boolean, packages: Set<String>): String {
        val value = listOf(sites, enabled.toString(), bypass.toString(), packages.sorted().joinToString("\n")).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun pending(desired: String, applied: String?, defaults: String): Boolean = desired != (applied ?: defaults)
}
