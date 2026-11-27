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
        // The record comes with the bundled seed overlaid, so its source and
        // operator names are complete — an empty one means "none", and
        // replaces a name the favorite still carries from an older seed.
        // Without the seed an empty name says nothing and the favorite keeps
        // what it knows.
        replacingMetadata: Boolean = false,
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
            // "Seit" is status like the rest: every path dates a disruption
            // from the span running now, so a record without a date means
            // there is none — it must not keep an old one alive.
            statusSinceEpochMillis = equipment.statusSinceEpochMillis,
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
            sourceName = if (replacingMetadata) equipment.sourceName else equipment.sourceName.ifEmpty { updated.sourceName },
            organizationName = if (replacingMetadata) {
                equipment.organizationName
            } else {
                equipment.organizationName.ifEmpty { updated.organizationName }
            },
            latitude = if (hasCoordinate) equipment.latitude else updated.latitude,
            longitude = if (hasCoordinate) equipment.longitude else updated.longitude,
            // Keeps the seed bridge fresh; absent on gap-station records,
            // where the number the favorite was created with survives.
            fastaEquipmentNumber = equipment.fastaEquipmentNumber ?: updated.fastaEquipmentNumber,
        )
    }
}
