package com.a11yland.hissi.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Bundled list of the DB stations across Germany with the FaSta equipment
// numbers of their elevators (app/src/main/assets/national-stations.json,
// generated in Hissi-iOS by scripts/generate-national-stations.py from the
// Aufzugsstatus API and copied per release like the seed).
// transit.accessibility.cloud carries these ~2500 DB elevators with status
// and (mostly) a centroid, but without a station; this list is what names
// them (EquipmentCatalog.nationalNamed), so DB stations beyond the VBB are
// searchable offline, favoritable under their numeric accessibility.cloud id
// and refreshed in the one favorites batch. Ported from
// `Shared/NationalStations.swift`; loading the asset is the app's job.
object NationalStations {
    @Serializable
    data class Station(
        // DB station number — Equipment.stationId for the records it names.
        val stationNumber: Int,
        val name: String,
        // Mean of the elevators' coordinates; absent for a station none of
        // whose lifts the API places.
        val latitude: Double? = null,
        val longitude: Double? = null,
        // FaSta equipment numbers = accessibility.cloud's
        // linked_data.operator_inventory_id.
        val lifts: List<Int> = emptyList(),
    )

    @Serializable
    private data class File(val stations: List<Station> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    // Pure parsing, unit-tested; anything unparseable yields an empty list.
    fun parse(data: String): List<Station> =
        runCatching { json.decodeFromString<File>(data).stations }.getOrDefault(emptyList())

    // Station per FaSta number, keyed as the inventory id string the live
    // records carry. First station wins should a number ever appear twice.
    fun byLift(stations: List<Station>): Map<String, Station> {
        val index = LinkedHashMap<String, Station>()
        for (station in stations) for (lift in station.lifts) index.putIfAbsent(lift.toString(), station)
        return index
    }
}
