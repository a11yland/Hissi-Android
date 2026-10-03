package com.a11yland.hissi.core

import java.time.Instant

// Folds a live catalog record into a favorite, pure and unit-tested. Ported
// from `Shared/StatusMerge.swift`. transit.accessibility.cloud is the only
// status source — the platform ingests the operator feeds itself.
object StatusMerge {
    fun apply(
        equipment: AccessibilityCloudClient.Equipment,
        elevator: MonitoredElevator,
        now: Instant = Instant.now(),
    ): MonitoredElevator {
        // A seed-id favorite resolved through the inventory-number bridge
        // (EquipmentCatalog.bridgedLiveIds) comes back as its live record —
        // adopt the live identity so the favorite matches search results and
        // refreshes by its numeric id from now on. The app persists the
        // migration (favorites store id migration).
        var updated = elevator
        if (equipment.id != elevator.id && equipment.id.toLongOrNull() != null) {
            updated = elevator.copy(
                id = equipment.id,
                stationId = equipment.stationId.ifEmpty { elevator.stationId },
                elevatorId = equipment.id,
            )
        }
        val hasCoordinate = equipment.latitude != null && equipment.longitude != null
        return updated.copy(
            stationName = equipment.stationName.ifEmpty { updated.stationName },
            elevatorDescription = equipment.description.ifEmpty { updated.elevatorDescription },
            isWorking = equipment.isWorking,
            lastCheckedEpochMillis = now.toEpochMilli(),
            lastUpdatedEpochMillis = equipment.lastUpdateEpochMillis,
            stateExplanation = equipment.stateExplanation,
            // Source, operator and coordinates are not served by the API —
            // they reach a record only through the seed overlay
            // (EquipmentCatalog). A refresh whose record carries none of them
            // therefore says nothing about them, and must not erase what the
            // favorite already knows; the status fields above are the
            // opposite case (a cleared disruption has to clear its reason).
            // Coordinates move as a pair.
            sourceName = equipment.sourceName.ifEmpty { updated.sourceName },
            organizationName = equipment.organizationName.ifEmpty { updated.organizationName },
            latitude = if (hasCoordinate) equipment.latitude else updated.latitude,
            longitude = if (hasCoordinate) equipment.longitude else updated.longitude,
            // Keeps the seed bridge fresh; absent on gap-station records,
            // where the number the favorite was created with survives.
            fastaEquipmentNumber = equipment.fastaEquipmentNumber ?: updated.fastaEquipmentNumber,
        )
    }
}
