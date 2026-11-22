package com.a11yland.hissi.core

import java.text.Normalizer

// The pure parts of the equipment catalog: the seed overlay and the
// legacy-id → live-id bridge. Ported from `Shared/EquipmentCatalog.swift`;
// the stateful cache lives in CatalogCache.
object EquipmentCatalog {
    // Maps format-1 seed ids (non-numeric — favorites created from an old
    // seed preview) to live ids via the seed's legacyIds. Pure, unit-tested.
    fun bridgedLiveIds(ids: Set<String>, legacyIds: Map<String, String>): Map<String, String> =
        ids.filter { it.toLongOrNull() == null }
            .mapNotNull { id -> legacyIds[id]?.let { id to it } }
            .toMap()

    // Pure merge, unit-tested. Seed and live share their ids, so a live
    // record takes from its seed record whatever it lacks — live always
    // wins. Typically that is the station (stationId, name, modes, region)
    // of an elevator upstream hasn't linked to a stop place, plus source and
    // operator names. A live record the seed doesn't know (added upstream
    // since the generation) falls back to the station: seed records at the
    // same station name supply coordinates, region and — when they all name
    // the same one — source/operator (see stationBackfilled). Seed records
    // only stand in for live ones when there is no live data at all
    // (offline, or a failed targeted fetch): then the whole seed is
    // returned, status unknown — never a false "working". With live data an
    // elevator missing from it is gone upstream and stays out. Whatever still
    // has no coordinate takes its station's from the `stations` list.
    fun overlaid(
        live: List<AccessibilityCloudClient.Equipment>,
        seed: List<SeedCatalog.Record>,
        stations: List<SeedCatalog.Station> = emptyList(),
    ): List<AccessibilityCloudClient.Equipment> {
        val stationsById = firstWins(stations.map { it.stationId to it })
        if (live.isEmpty()) return seed.map { stationCoordinated(equipment(it), stationsById) }
        val seedById = firstWins(seed.map { it.id to it })
        val seedByStation = seed
            .filter { stationKey(it.stationName).isNotEmpty() }
            .groupBy { stationKey(it.stationName) }
        return live.map { equipment ->
            val record = seedById[equipment.id]
                ?: return@map stationCoordinated(stationBackfilled(equipment, seedByStation), stationsById)
            // The seed's station replaces the live one only when live has
            // none — id, name and modes travel together.
            val hasStation = equipment.stationName.isNotEmpty()
            stationCoordinated(
                equipment.copy(
                    inventoryId = equipment.inventoryId ?: record.inventoryId,
                    stationId = if (hasStation && equipment.stationId.isNotEmpty()) equipment.stationId else record.stationId,
                    stationName = if (hasStation) equipment.stationName else record.stationName,
                    stationNetworks = if (hasStation && equipment.stationNetworks.isNotEmpty()) {
                        equipment.stationNetworks
                    } else {
                        record.networks
                    },
                    description = equipment.description.ifEmpty { record.description },
                    sourceName = equipment.sourceName.ifEmpty { record.sourceName },
                    organizationName = equipment.organizationName.ifEmpty { record.organizationName },
                    latitude = equipment.latitude ?: record.latitude,
                    longitude = equipment.longitude ?: record.longitude,
                    region = equipment.region ?: record.transitRegion,
                ),
                stationsById,
            )
        }
    }

    // A seed record as a status-unknown catalog record.
    fun equipment(record: SeedCatalog.Record): AccessibilityCloudClient.Equipment =
        AccessibilityCloudClient.Equipment(
            id = record.id,
            inventoryId = record.inventoryId,
            stationId = record.stationId,
            stationName = record.stationName,
            stationNetworks = record.networks,
            description = record.description,
            isWorking = null,
            lastUpdateEpochMillis = null,
            sourceName = record.sourceName,
            organizationName = record.organizationName,
            latitude = record.latitude,
            longitude = record.longitude,
            region = record.transitRegion,
        )

    // The station's own coordinate (and region) for a record that has none —
    // after the live centroid and the seed's siblings, never instead of them.
    // Pure, unit-tested.
    fun stationCoordinated(
        equipment: AccessibilityCloudClient.Equipment,
        stationsById: Map<String, SeedCatalog.Station>,
    ): AccessibilityCloudClient.Equipment {
        val needsCoordinate = equipment.latitude == null || equipment.longitude == null
        if (!needsCoordinate && equipment.region != null) return equipment
        val station = stationsById[equipment.stationId] ?: return equipment
        return equipment.copy(
            latitude = if (needsCoordinate) station.latitude else equipment.latitude,
            longitude = if (needsCoordinate) station.longitude else equipment.longitude,
            region = equipment.region ?: TransitRegion.fromRawValue(station.region),
        )
    }

    // What a live record the inventory number answered incompletely (or not
    // at all) still gets from the seed: the metadata that holds for a whole
    // station. The API serves no source/operator names and no coordinates for
    // some records, so without this an unmatched record loses its map and both name rows in
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
