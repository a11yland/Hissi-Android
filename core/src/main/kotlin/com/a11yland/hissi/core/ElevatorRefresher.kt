package com.a11yland.hissi.core

// Driven port for the refresh orchestration: the status source as an
// injectable function. The app wires up the real catalog
// (CatalogCache::equipment); tests substitute a stub, which is what makes the
// logic below unit-testable at all. Ported from `Shared/ElevatorRefresher.swift`.
data class StatusSources(
    // Batch resolution against transit.accessibility.cloud: one targeted
    // request (or a still-fresh catalog cache) answers all favorites at once —
    // never one request per favorite, never a full catalog build. Keys of the
    // result = the requested ids.
    val equipment: suspend (Set<String>) -> Map<String, AccessibilityCloudClient.Equipment>,
)

// Refreshes all favorites in one catalog batch. transit.accessibility.cloud
// is the only status source — it ingests the operator feeds (incl. DB FaSta)
// itself. On failure an elevator keeps its previous values and its id is
// reported back. (The iOS one-time legacy-favorites cleanup has no Android
// counterpart: this app never stored pre-migration ids.)
object ElevatorRefresher {
    data class Result(
        val elevators: List<MonitoredElevator>,
        val failedStations: Set<String>,
    )

    suspend fun refreshAll(elevators: List<MonitoredElevator>, sources: StatusSources): Result {
        if (elevators.isEmpty()) return Result(emptyList(), emptySet())
        val equipmentById = sources.equipment(elevators.map { it.id }.toSet())

        val failed = mutableSetOf<String>()
        val updated = elevators.map { elevator ->
            val equipment = equipmentById[elevator.id]
            if (equipment == null) {
                failed.add(elevator.id)
                elevator
            } else {
                StatusMerge.apply(equipment, elevator)
            }
        }
        return Result(updated, failed)
    }
}
