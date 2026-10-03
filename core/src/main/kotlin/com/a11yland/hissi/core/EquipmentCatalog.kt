package com.a11yland.hissi.core

import java.text.Normalizer

// The pure parts of the equipment catalog: the seed overlay and the
// seed-id → live-id bridge. Ported from `Shared/EquipmentCatalog.swift`; the
// stateful cache (TTL, disk persistence, shared in-flight build) follows in
// the app phase.
object EquipmentCatalog {
    // Maps seed-record favorite ids (non-numeric) to the id of their live
    // record, matched via the operator inventory number (= the seed's FaSta
    // equipment number, stable across both APIs). Pure, unit-tested. Only
    // numeric candidates count as live — an appended seed record (which also
    // carries the number) must not bridge to itself.
    fun bridgedLiveIds(
        ids: Set<String>,
        seed: List<SeedCatalog.Record>,
        catalog: List<AccessibilityCloudClient.Equipment>,
    ): Map<String, String> {
        val seedIds = ids.filter { it.toLongOrNull() == null }
        if (seedIds.isEmpty() || catalog.isEmpty()) return emptyMap()
        val seedById = firstWins(seed.map { it.id to it })
        val liveByNumber = firstWins(
            catalog.mapNotNull { record ->
                if (record.id.toLongOrNull() == null) return@mapNotNull null
                val number = record.fastaEquipmentNumber ?: record.inventoryId?.toIntOrNull()
                number?.let { it to record.id }
            }
        )
        return seedIds.mapNotNull { id ->
            val number = seedById[id]?.fastaEquipmentNumber ?: return@mapNotNull null
            liveByNumber[number]?.let { id to it }
        }.toMap()
    }

    // Pure merge, unit-tested. `live` wins; matching runs over the operator
    // inventory number (= the seed's FaSta equipment number, stable across
    // both APIs). Matched records gain the seed's station/source/operator
    // names, coordinates and region; whatever the match leaves empty — and
    // everything on a record the number matches nothing for — falls back to
    // the station (see stationBackfilled). Seed records the live catalog does not
    // answer for are appended with unknown status — never a false "working":
    // "fasta" records whose elevator is missing upstream, "brokenlifts"
    // records while their whole station is absent (at a live station a stale
    // record would just sit next to the real elevators), and the entire seed
    // when offline. Unmatched accessibilityCloud records stay out either way
    // — their stations are live, appending them would duplicate elevators.
    fun overlaid(
        live: List<AccessibilityCloudClient.Equipment>,
        seed: List<SeedCatalog.Record>,
    ): List<AccessibilityCloudClient.Equipment> {
        val seedByInventory = firstWins(
            seed.mapNotNull { record ->
                record.fastaEquipmentNumber?.let { it.toString() to record }
            }
        )

        // Seed records by station, for the fallback below. Built from the
        // whole seed, matched or not: at a station where two of three lifts
        // matched, the third is exactly the record that needs the other two.
        val seedByStation = seed
            .filter { stationKey(it.stationName).isNotEmpty() }
            .groupBy { stationKey(it.stationName) }

        val matchedSeedIds = mutableSetOf<String>()
        val merged = live.map { equipment ->
            val record = equipment.inventoryId?.let(seedByInventory::get)
                ?: return@map stationBackfilled(equipment, seedByStation)
            matchedSeedIds.add(record.id)
            // Backfilled again on the way out: a matched seed record can
            // itself carry no coordinates (Waßmannsdorf), which its siblings
            // at the same station do.
            stationBackfilled(
                equipment.copy(
                    stationName = equipment.stationName.ifEmpty { record.stationName },
                    sourceName = equipment.sourceName.ifEmpty { record.sourceName },
                    organizationName = equipment.organizationName.ifEmpty { record.organizationName },
                    latitude = equipment.latitude ?: record.latitude,
                    longitude = equipment.longitude ?: record.longitude,
                    fastaEquipmentNumber = record.fastaEquipmentNumber,
                    region = equipment.region ?: TransitRegion.fromRawValue(record.region),
                ),
                seedByStation,
            )
        }

        val liveStationIds = merged.map { it.stationId }.toSet()
        val appended = merged.toMutableList()
        for (record in seed) {
            if (record.id in matchedSeedIds) continue
            val append = when {
                live.isEmpty() -> true
                record.source == "fasta" -> true
                record.source == "brokenlifts"
                    && record.brokenliftsStationId.orEmpty() !in liveStationIds -> true
                else -> false
            }
            if (!append) continue
            appended.add(
                AccessibilityCloudClient.Equipment(
                    id = record.id,
                    stationId = record.brokenliftsStationId
                        ?: record.stationNumber?.toString() ?: "",
                    stationName = record.stationName,
                    description = record.description,
                    isWorking = null,
                    lastUpdateEpochMillis = null,
                    sourceName = record.sourceName,
                    organizationName = record.organizationName,
                    latitude = record.latitude,
                    longitude = record.longitude,
                    fastaEquipmentNumber = record.fastaEquipmentNumber,
                    brokenliftsIndex = record.brokenliftsIndex,
                    region = TransitRegion.fromRawValue(record.region),
                )
            )
        }
        return appended
    }

    // What a live record the inventory number answered incompletely (or not
    // at all) still gets from the seed: the metadata that holds for a whole
    // station. The API serves neither coordinates nor source/operator names,
    // so without this an unmatched record loses its map and both name rows in
    // the detail view — even though the bundle knows the station. Two guards
    // keep the fallback honest: it only fills what is still missing (live
    // always wins), and source/operator are adopted only when every seed
    // record at the station names the same one, so a station served by two
    // operators keeps those rows empty rather than claiming the wrong one.
    // The coordinates are a sibling lift's, not this one's — the detail map
    // frames the station, where that is the same place. The inventory number
    // is never taken from a sibling: it identifies the elevator, not the
    // station, and favorites bridge on it. Pure, unit-tested.
    fun stationBackfilled(
        equipment: AccessibilityCloudClient.Equipment,
        seedByStation: Map<String, List<SeedCatalog.Record>>,
    ): AccessibilityCloudClient.Equipment {
        val needsCoordinate = equipment.latitude == null || equipment.longitude == null
        if (!needsCoordinate && equipment.sourceName.isNotEmpty() &&
            equipment.organizationName.isNotEmpty() && equipment.region != null
        ) {
            return equipment
        }
        val records = seedByStation[stationKey(equipment.stationName)] ?: return equipment
        val coordinate = if (needsCoordinate) {
            records.firstOrNull { it.latitude != null && it.longitude != null }
        } else {
            null
        }
        return equipment.copy(
            sourceName = equipment.sourceName
                .ifEmpty { unanimous(records.map { it.sourceName }).orEmpty() },
            organizationName = equipment.organizationName
                .ifEmpty { unanimous(records.map { it.organizationName }).orEmpty() },
            latitude = equipment.latitude ?: coordinate?.latitude,
            longitude = equipment.longitude ?: coordinate?.longitude,
            region = equipment.region
                ?: records.firstNotNullOfOrNull { TransitRegion.fromRawValue(it.region) },
        )
    }

    // Station names across the two datasets differ by their network prefix:
    // the seed carries the VBB/BVG spelling ("U Spittelmarkt (Berlin)"), the
    // live catalog the stop place's ("Spittelmarkt (Berlin)"). Folding that
    // prefix away (plus case and diacritics) is deliberately all this does —
    // merging "S Pankow" with "U Pankow" is right, they are one station,
    // while a looser key (dropping "(Berlin)", tolerating suffixes) would
    // start merging stations that only share a name. Pure, unit-tested.
    fun stationKey(stationName: String): String {
        val name = combiningMarks
            .replace(Normalizer.normalize(stationName, Normalizer.Form.NFD), "")
            .lowercase()
            .trim()
        for (prefix in listOf("s+u ", "u ", "s ")) {
            if (name.startsWith(prefix)) return name.removePrefix(prefix).trim()
        }
        return name
    }

    // "ß" is its own letter, not a diacritic, so it stays — same folding as
    // the name search.
    private val combiningMarks = Regex("\\p{Mn}+")

    // The one value they all name, or null when they disagree (or say nothing).
    private fun unanimous(values: List<String>): String? =
        values.filter { it.isNotEmpty() }.distinct().singleOrNull()

    private fun <K, V> firstWins(pairs: List<Pair<K, V>>): Map<K, V> {
        val map = LinkedHashMap<K, V>(pairs.size)
        for ((key, value) in pairs) map.putIfAbsent(key, value)
        return map
    }
}
