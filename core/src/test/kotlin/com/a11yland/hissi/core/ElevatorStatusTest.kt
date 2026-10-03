package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals

// Ported 1:1 from HissiTests/Tests/ElevatorStatusTests.swift.
class ElevatorStatusTest {
    @Test
    fun mapsIsWorking() {
        assertEquals(ElevatorStatus.Working, ElevatorStatus.from(isWorking = true))
        assertEquals(ElevatorStatus.Broken, ElevatorStatus.from(isWorking = false))
        assertEquals(ElevatorStatus.Unknown, ElevatorStatus.from(isWorking = null))
    }

    // Icons must differ by shape, not only colour — the a11y contract.
    @Test
    fun symbolsAreDistinctPerStatus() {
        val icons = listOf(ElevatorStatus.Working, ElevatorStatus.Broken, ElevatorStatus.Unknown)
            .map { it.icon }.toSet()
        assertEquals(3, icons.size)
    }

    @Test
    fun labelsAreDistinctPerStatus() {
        val statuses = listOf(ElevatorStatus.Working, ElevatorStatus.Broken, ElevatorStatus.Unknown)
        assertEquals(3, statuses.map { it.label }.toSet().size)
        assertEquals(3, statuses.map { it.shortLabel }.toSet().size)
    }
}
