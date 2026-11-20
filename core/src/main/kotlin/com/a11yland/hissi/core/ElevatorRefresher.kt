package com.a11yland.hissi.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    // Resolution against the Aufzugsstatus API for the favorites beyond the
    // VBB (namespaced ids, AufzugsstatusClient.isNationalId): one request per
    // id — the API has no batch endpoint — so only ever asked for the handful
    // of national favorites, never for a catalog. Keys = requested ids.
    val national: suspend (Set<String>) -> Map<String, AccessibilityCloudClient.Equipment> = { emptyMap() },
) {
    // Splits a mixed id set by source, asks both in parallel and merges the
    // answers — the one routing step every status lookup goes through
    // (favorites refresh, a search result's detail). A source with nothing
    // to resolve is not called.
    suspend fun resolve(ids: Set<String>): Map<String, AccessibilityCloudClient.Equipment> = coroutineScope {
        val nationalIds = ids.filter(AufzugsstatusClient::isNationalId).toSet()
        val localIds = ids - nationalIds
        val local = async { if (localIds.isEmpty()) emptyMap() else equipment(localIds) }
        val beyond = async { if (nationalIds.isEmpty()) emptyMap() else national(nationalIds) }
        beyond.await() + local.await()
    }
}

// Refreshes all favorites: the VBB ones in one catalog batch against
// transit.accessibility.cloud (which ingests the operator feeds, incl. DB
// FaSta, itself), the ones beyond the VBB against the Aufzugsstatus API, both
// in parallel (StatusSources.resolve). On failure an elevator keeps its
// previous values and its id is reported back. (The iOS one-time
// legacy-favorites cleanup has no Android counterpart: this app never stored
// pre-migration ids.)
object ElevatorRefresher {
    data class Result(
        val elevators: List<MonitoredElevator>,
        val failedStations: Set<String>,
    )

    suspend fun refreshAll(elevators: List<MonitoredElevator>, sources: StatusSources): Result {
        if (elevators.isEmpty()) return Result(emptyList(), emptySet())
        val equipmentById = sources.resolve(elevators.map { it.id }.toSet())

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
