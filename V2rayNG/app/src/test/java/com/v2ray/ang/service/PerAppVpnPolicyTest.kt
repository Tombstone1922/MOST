package com.v2ray.ang.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PerAppVpnPolicyTest {
    @Test fun allAppsExcludeOnlyTheVpnApp() {
        assertEquals(PerAppVpnPolicy(false, setOf("self")), PerAppVpnPolicy.create(false, true, setOf("browser"), "self"))
    }
    @Test fun onlySelectedAppsUseVpnAndInputIsNotMutated() {
        val selected = mutableSetOf("self", "browser")
        assertEquals(PerAppVpnPolicy(true, setOf("browser")), PerAppVpnPolicy.create(true, false, selected, "self"))
        assertEquals(setOf("self", "browser"), selected)
    }
    @Test fun bypassListAlwaysExcludesTheVpnApp() {
        assertEquals(PerAppVpnPolicy(false, setOf("browser", "self")), PerAppVpnPolicy.create(true, true, setOf("browser"), "self"))
    }
    @Test fun emptyOnlySelectionDoesNotFallBackToAllAppsPolicy() {
        assertEquals(PerAppVpnPolicy(true, emptySet<String>()), PerAppVpnPolicy.create(true, false, emptySet(), "self"))
        assertEquals(PerAppVpnPolicy(false, setOf("self")), PerAppVpnPolicy.create(true, true, emptySet(), "self"))
    }
}
