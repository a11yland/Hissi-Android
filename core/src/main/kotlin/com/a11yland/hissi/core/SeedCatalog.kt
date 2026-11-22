package com.a11yland.hissi.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Bundled snapshot of the transit.accessibility.cloud elevator catalog
// (format 2), generated per release by scripts/generate-seed-catalog.py in
// Hissi-iOS — the same `seed-catalog.json` the iOS app bundles, copied into
// app/src/main/assets per release. Every record is a live elevator under its
// live numeric id, so a favorite created from the seed is a live favorite
// from the start. The seed backfills what the live depth=0 fetch doesn't
// carry — the station of elevators upstream hasn't linked to a stop place
// (all DB elevators outside Berlin/Brandenburg; the generator places them at
// the nearest rail station), source and operator names, region and station
// transport modes — and carries the search when offline. The `stations` list
// holds a coordinate for every station the seed uses; `legacyIds` maps the
// ids of the format-1 seed onto live ids.
// Ported from `Shared/SeedCatalog.swift`; loading the bundled asset is the
// app's job (there is no Bundle on the JVM), parsing stays pure and tested.
object SeedCatalog {
    @Serializable
    data class Record(
        val id: String,                // live transit.accessibility.cloud id
        val inventoryId: String? = null,
        // National station number ("900100015") for platform stop places,
        // "name:<station>" for stations the generator assigned.
        val stationId: String = "",
        val stationName: String = "",
        // Stop-place transport modes (1 = S-Bahn, 2 = U-Bahn, 3 = Regional-
        // und Fernbahn); empty for assigned stations.
        val stationModes: List<Int> = emptyList(),
        // TransitRegion raw value (federal state).
        val region: String? = null,
        val description: String = "",
        val latitude: Double? = null,
        val longitude: Double? = null,
        val sourceName: String = "",
        val organizationName: String = "",
    ) {
        val transitRegion: TransitRegion? get() = TransitRegion.fromRawValue(region)
        val networks: Set<TransitNetwork>
            get() = stationModes.mapNotNull(TransitNetwork::fromTransportModeId).toSet()
    }

    @Serializable
    data class Station(
        // Equipment.stationId.
        val stationId: String,
        val dhid: String? = null,
        val name: String,
        val region: String? = null,
        val latitude: Double,
        val longitude: Double,
    )

    @Serializable
    private data class File(
        val elevators: List<Record>,
        val stations: List<Station> = emptyList(),
        val legacyIds: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(data: String): File? = runCatching { json.decodeFromString<File>(data) }.getOrNull()

    // Pure parsing, unit-tested; anything unparseable yields an empty seed.
    fun parse(data: String): List<Record> = decode(data)?.elevators.orEmpty()

    fun parseStations(data: String): List<Station> = decode(data)?.stations.orEmpty()

    fun parseLegacyIds(data: String): Map<String, String> = decode(data)?.legacyIds.orEmpty()
}
