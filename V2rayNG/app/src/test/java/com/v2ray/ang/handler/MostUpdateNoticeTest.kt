package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MostUpdateNoticeTest {
    @Test
    fun `newer release is announced once and only when automatic checking is enabled`() {
        assertTrue(UpdateCheckerManager.shouldNotify(true, 4000008, 4000009, 0))
        assertFalse(UpdateCheckerManager.shouldNotify(true, 4000008, 4000009, 4000009))
        assertFalse(UpdateCheckerManager.shouldNotify(true, 4000008, 4000009, 4000010))
        assertFalse(UpdateCheckerManager.shouldNotify(false, 4000008, 4000009, 0))
        assertFalse(UpdateCheckerManager.shouldNotify(true, 4000008, 4000008, 0))
        assertFalse(UpdateCheckerManager.shouldNotify(true, 4000008, 4000007, 0))
        assertTrue(UpdateCheckerManager.shouldNotify(true, 4000008, 4000010, 4000009))
    }
}
