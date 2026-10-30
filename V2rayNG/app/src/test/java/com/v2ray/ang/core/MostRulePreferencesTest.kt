package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MostRulePreferencesTest {
    @Test fun onlyAppliedValueClearsPendingAndPackageOrderDoesNotMatter() {
        val defaults = MostRulePreferences.fingerprint("sites", false, false, emptySet())
        assertFalse(MostRulePreferences.pending(defaults, null, defaults))
        val edited = MostRulePreferences.fingerprint("new-sites", false, false, emptySet())
        assertTrue(MostRulePreferences.pending(edited, defaults, defaults))
        assertTrue(MostRulePreferences.pending(edited, null, defaults))
        assertFalse(MostRulePreferences.pending(edited, edited, defaults))
        val apps = MostRulePreferences.fingerprint("sites", true, true, setOf("one", "two"))
        assertEquals(apps, MostRulePreferences.fingerprint("sites", true, true, setOf("two", "one")))
        assertNotEquals(apps, MostRulePreferences.fingerprint("sites", true, false, setOf("one", "two")))
    }
}
