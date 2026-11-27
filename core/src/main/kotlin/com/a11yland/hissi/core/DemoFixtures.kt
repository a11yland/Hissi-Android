package com.a11yland.hissi.core

// The catalog demo mode serves instead of the API (Play screenshots): every
// bundled seed record as a live record under its own id and a fixed status —
// mostly working, a few curated disruptions at stations the screenshots
// show. Derived from the seed rather than written by hand so search and
// nearby look like the real thing; the seed overlay matches every record by
// id. Dates are relative to `nowMillis`: fixed dates would drift into
// "vor 3 Tagen". Ported from `Shared/DemoFixtures.swift` (same picks, so
// both stores show the same stations). Pure, unit-tested.
object DemoFixtures {
    data class Pick(val station: String, val description: String) {
        fun matches(record: SeedCatalog.Record): Boolean =
            station in record.stationName && description in record.description
    }

    val broken = listOf(
        Pick("Alexanderplatz", "U8 ↔ Passage"),
        Pick("Hackescher Markt", "(S-Bahn)"),
    )
    val unknown = listOf(
        Pick("Schillingstraße", "Straße ↔ Bahnsteig"),
    )

    // The favorites a screenshot run starts with, newest first.
    val favoritePicks = listOf(
        Pick("Alexanderplatz", "U2 ↔ Passage"),
        Pick("Alexanderplatz", "U8 ↔ Passage"),
        Pick("Berlin Hauptbahnhof", "↔ U5"),
        Pick("Pankow (Berlin)", "Bahnsteig U2"),
    )

    private const val DAY_MILLIS = 86_400_000L
    private const val MINUTE_MILLIS = 60_000L

    fun catalog(
        seed: List<SeedCatalog.Record>,
        nowMillis: Long,
        language: String,
    ): List<AccessibilityCloudClient.Equipment> {
        // Each pick names one elevator: its first match.
        fun firstMatches(picks: List<Pick>) =
            picks.mapNotNull { pick -> seed.indexOfFirst(pick::matches).takeIf { it >= 0 } }.toSet()
        val brokenIndices = firstMatches(broken)
        val unknownIndices = firstMatches(unknown)
        return seed.mapIndexed { index, record ->
            val isBroken = index in brokenIndices
            val isUnknown = index in unknownIndices
            // Spread the "Stand" times over the last three hours; a
            // disruption is older than that.
            val minutesAgo = if (isBroken) 26 * 60 else 8 + (index * 37) % 170
            // "Seit": the disruption began with its "Stand"; working lifts
            // have been running for days, an unknown status is recent.
            val sinceMinutesAgo = when {
                isBroken -> minutesAgo
                isUnknown -> 2 * 60
                else -> (2 + index % 19) * 1440
            }
            AccessibilityCloudClient.Equipment(
                id = record.id,
                inventoryId = record.inventoryId,
                stationId = record.stationId,
                stationName = record.stationName,
                stationNetworks = record.networks,
                description = record.description,
                isWorking = if (isUnknown) null else !isBroken,
                lastUpdateEpochMillis = if (isUnknown) null else nowMillis - minutesAgo * MINUTE_MILLIS,
                statusSinceEpochMillis = nowMillis - sinceMinutesAgo * MINUTE_MILLIS,
                sourceName = record.sourceName,
                organizationName = record.organizationName,
                stateExplanation = if (isBroken) explanation(language) else null,
                latitude = record.latitude,
                longitude = record.longitude,
                region = record.transitRegion,
            )
        }
    }

    fun favorites(
        catalog: List<AccessibilityCloudClient.Equipment>,
        nowMillis: Long,
    ): List<MonitoredElevator> = favoritePicks.mapIndexedNotNull { offset, pick ->
        val equipment = catalog.firstOrNull {
            pick.station in it.stationName && pick.description in it.description
        } ?: return@mapIndexedNotNull null
        MonitoredElevator(
            id = equipment.id,
            stationId = equipment.stationId,
            elevatorId = equipment.id,
            stationName = equipment.stationName,
            elevatorDescription = equipment.description,
            isWorking = equipment.isWorking,
            lastCheckedEpochMillis = nowMillis,
            lastUpdatedEpochMillis = equipment.lastUpdateEpochMillis,
            statusSinceEpochMillis = equipment.statusSinceEpochMillis,
            sourceName = equipment.sourceName,
            organizationName = equipment.organizationName,
            stateExplanation = equipment.stateExplanation,
            latitude = equipment.latitude,
            longitude = equipment.longitude,
            addedAtEpochMillis = nowMillis - offset * DAY_MILLIS,
        )
    }

    private fun explanation(language: String): String =
        if (language.startsWith("en")) "Out of order – repair scheduled" else "Außer Betrieb – Reparatur beauftragt"
}
