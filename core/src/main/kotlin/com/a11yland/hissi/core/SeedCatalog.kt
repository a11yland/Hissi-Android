package com.a11yland.hissi.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Bundled snapshot of the merged elevator catalog (accessibility.cloud +
// DB FaSta + brokenlifts.org), generated per release by
// scripts/generate-seed-catalog.py in Hissi-iOS — the same `seed-catalog.json`
// the iOS app bundles, copied into app/src/main/assets per release. The seed
// backfills what the API doesn't serve (names, coordinates where a record
// carries no centroid, region), keeps gap stations searchable and carries the
// search when offline. The `stations` list is the last coordinate fallback:
// stations whose live elevators have no centroid and that no seed record
// places either, keyed by the station number (VBB GTFS coordinates).
// Ported from `Shared/SeedCatalog.swift`; loading the bundled asset is the
// app's job (there is no Bundle on the JVM), parsing stays pure and tested.
object SeedCatalog {
    @Serializable
    data class Record(
        val id: String,
        val source: String,            // "accessibilityCloud" | "fasta" | "brokenlifts"
        val acId: String? = null,
        val fastaEquipmentNumber: Int? = null,
        // DB station number, only on FaSta-only records.
        val stationNumber: Int? = null,
        // VBB station number of the brokenlifts.org page and the elevator's
        // position on it, only on brokenlifts records.
        val brokenliftsStationId: String? = null,
        val brokenliftsIndex: Int? = null,
        val stationName: String,
        val description: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val sourceName: String,
        val organizationName: String,
        // "berlin" | "brandenburg"; null in seeds generated before the field
        // existed (the catalog then falls back to the name heuristic).
        val region: String? = null,
    )

    @Serializable
    data class Station(
        // National station number ("900100015") — Equipment.stationId.
        val stationId: String,
        val dhid: String? = null,
        val name: String,
        val latitude: Double,
        val longitude: Double,
    )

    @Serializable
    // `stations` is absent in seeds generated before the list existed.
    private data class File(val elevators: List<Record>, val stations: List<Station> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    // Pure parsing, unit-tested; anything unparseable yields an empty seed.
    fun parse(data: String): List<Record> =
        runCatching { json.decodeFromString<File>(data).elevators }.getOrDefault(emptyList())

    fun parseStations(data: String): List<Station> =
        runCatching { json.decodeFromString<File>(data).stations }.getOrDefault(emptyList())
}
