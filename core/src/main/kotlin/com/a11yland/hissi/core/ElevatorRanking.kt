package com.a11yland.hissi.core

// Urgency order for every surface that has to cut the favorites short: broken
// first, then unknown, then working — a broken elevator is the whole point of
// the app and must survive the cap. Stable within each group (sortedBy is
// stable in Kotlin, but the explicit index tie-breaker keeps the contract
// spelled out like the Swift original). Ported from
// `Shared/ElevatorRanking.swift`.
object ElevatorRanking {
    fun byUrgency(elevators: List<MonitoredElevator>): List<MonitoredElevator> =
        elevators.withIndex()
            .sortedWith(compareBy({ rank(it.value) }, { it.index }))
            .map { it.value }

    private fun rank(elevator: MonitoredElevator): Int = when (elevator.isWorking) {
        false -> 0
        null -> 1
        true -> 2
    }
}
