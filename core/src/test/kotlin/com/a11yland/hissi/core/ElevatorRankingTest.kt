package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ElevatorRankingTest {
    private fun elevator(id: String, isWorking: Boolean?) = MonitoredElevator(
        id = id,
        stationId = "s",
        elevatorId = id,
        stationName = "Station",
        elevatorDescription = "",
        isWorking = isWorking,
    )

    @Test
    fun brokenOutranksUnknownOutranksWorking() {
        val ranked = ElevatorRanking.byUrgency(
            listOf(
                elevator("working", true),
                elevator("unknown", null),
                elevator("broken", false),
            ),
        )
        assertEquals(listOf("broken", "unknown", "working"), ranked.map { it.id })
    }

    @Test
    fun orderWithinAGroupStaysStable() {
        val ranked = ElevatorRanking.byUrgency(
            listOf(
                elevator("a", true),
                elevator("b", false),
                elevator("c", true),
                elevator("d", false),
            ),
        )
        assertEquals(listOf("b", "d", "a", "c"), ranked.map { it.id })
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList(), ElevatorRanking.byUrgency(emptyList()))
    }
}
