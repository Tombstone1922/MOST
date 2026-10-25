package com.v2ray.ang.service

/** Deterministic Android VPN package policy, separate from the framework Builder. */
internal data class PerAppVpnPolicy(val allowOnly: Boolean, val packages: Set<String>) {
    companion object {
        fun create(enabled: Boolean, bypass: Boolean, selected: Set<String>, self: String): PerAppVpnPolicy =
            when {
                !enabled -> PerAppVpnPolicy(false, setOf(self))
                bypass -> PerAppVpnPolicy(false, selected + self)
                else -> PerAppVpnPolicy(true, selected - self)
            }
    }
}
