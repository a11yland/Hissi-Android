package com.a11yland.hissi.core

import java.text.Collator
import java.time.Instant
import java.util.Locale

// Pure grouping behind the search results list, ported from
// `Shared/SearchGrouping.swift`: region → station (alphabetical) → network.

data class StationSearchResult(val stationId: String, val name: String) {
    val id: String get() = stationId
}

// Elevators of one station that belong to the same network, in display order
// within the station. The subheader is only rendered when a station spans
// several networks (S+U stations).
data class NetworkSection(val network: TransitNetwork, val elevators: List<MonitoredElevator>) {
    val id: Int get() = network.ordinal
}

// A station grouping one or more elevators for the search results list.
data class StationGroup(val station: StationSearchResult, val sections: List<NetworkSection>) {
    val id: String get() = station.stationId
    val networks: List<TransitNetwork> get() = sections.map { it.network }
}

// Top level of the results: Berlin before Brandenburg. The header is only
// rendered when the results span both regions.
data class RegionGroup(val region: TransitRegion, val stations: List<StationGroup>) {
    val id: String get() = region.rawValue
}

object SearchGrouping {
    // Locale-aware, case-insensitive station ordering (the Swift port point
    // uses localizedCaseInsensitiveCompare); German collation sorts umlauts
    // next to their base letters.
    private val collator: Collator = Collator.getInstance(Locale.GERMAN).apply {
        strength = Collator.SECONDARY
    }

    // Grouping stations on the name (not the source-specific station id)
    // merges S+U stations whose U-Bahn and S-Bahn elevators come from
    // different sources. A station's region comes from the first record that
    // knows it, with the name heuristic as fallback.
    fun group(equipment: List<AccessibilityCloudClient.Equipment>): List<RegionGroup> {
        val stations = equipment.groupBy { it.stationName }
            .map { (name, items) ->
                val region = items.firstNotNullOfOrNull { it.region }
                    ?: TransitRegion.inferred(name)
                region to StationGroup(
                    station = StationSearchResult(stationId = name, name = name),
                    sections = networkSections(items),
                )
            }

        return TransitRegion.entries.mapNotNull { region ->
            val matching = stations
                .filter { it.first == region }
                .map { it.second }
                .sortedWith(compareBy(collator) { it.station.name })
            if (matching.isEmpty()) null else RegionGroup(region = region, stations = matching)
        }
    }

    // Swaps one elevator in the grouped results for a fresher copy — what the
    // detail view fetched for a search result, folded back into the row behind
    // it. Matched by the id the row carries; the replacement may bring a new
    // id (a seed record resolved to its live one), the row adopts it. Nothing
    // about grouping or order changes.
    fun replacing(regions: List<RegionGroup>, id: String, elevator: MonitoredElevator): List<RegionGroup> =
        regions.map { region ->
            region.copy(
                stations = region.stations.map { station ->
                    station.copy(
                        sections = station.sections.map { section ->
                            section.copy(
                                elevators = section.elevators.map { if (it.id == id) elevator else it },
                            )
                        },
                    )
                },
            )
        }

    private fun networkSections(items: List<AccessibilityCloudClient.Equipment>): List<NetworkSection> =
        items.groupBy { equipment ->
            TransitNetwork.classify(
                description = equipment.description,
                stationName = equipment.stationName,
                sourceName = equipment.sourceName,
                stationModes = equipment.stationNetworks,
            )
        }
            .map { (network, grouped) ->
                NetworkSection(network = network, elevators = grouped.map(::monitored))
            }
            .sortedBy { it.network }

    private fun monitored(equipment: AccessibilityCloudClient.Equipment): MonitoredElevator =
        MonitoredElevator(
            id = equipment.id,
            stationId = equipment.stationId,
            elevatorId = equipment.id,
            // brokenlifts favorites refresh by page position.
            elevatorIndex = equipment.brokenliftsIndex ?: 0,
            stationName = equipment.stationName,
            elevatorDescription = equipment.description,
            isWorking = equipment.isWorking,
            lastCheckedEpochMillis = Instant.now().toEpochMilli(),
            lastUpdatedEpochMillis = equipment.lastUpdateEpochMillis,
            sourceName = equipment.sourceName,
            organizationName = equipment.organizationName,
            stateExplanation = equipment.stateExplanation,
            latitude = equipment.latitude,
            longitude = equipment.longitude,
            fastaEquipmentNumber = equipment.fastaEquipmentNumber,
        )
}
