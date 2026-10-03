package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

// Wiring test for the phase-1 skeleton; the real ports (phase 2) bring the
// fixture-backed suites.
class RefreshIntervalTest {
    @Test
    fun regularCadenceIsThirtyMinutes() {
        assertEquals(30.minutes, RefreshInterval.interval)
    }

    @Test
    fun cacheReuseWindowIsShorterThanTheCadence() {
        assertTrue(RefreshInterval.cacheReuse < RefreshInterval.interval)
    }
}
