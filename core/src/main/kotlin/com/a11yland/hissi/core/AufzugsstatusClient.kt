package com.a11yland.hissi.core

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The pure half of the Aufzugsstatus API client, ported from
// `Shared/AufzugsstatusClient.swift` (the network half is AufzugsstatusApi).
// The API (https://aufzuege.ploetzlich-querschnitt.de/api/, run by plötzlich
// querschnitt) aggregates the elevator disruption feeds of Deutsche Bahn (DB
// InfraGO FaSta, nationwide) and local operators (HVV, KVB, MVG, VAG
// Nürnberg, …), polled every five minutes upstream. It is Hissi's **second**
// status source, for everything beyond the VBB: transit.accessibility.cloud
// stays the source for Berlin and Brandenburg.
//
// Three rules keep the two sources from colliding:
// 1. Ids are namespaced: the API's own ids ("fasta:10804989",
//    "hvv:Master:11950-E") are the favorite ids verbatim — `isNationalId`
//    tells them from the numeric accessibility.cloud ids, and every refresh
//    routes on that (StatusSources.resolve).
// 2. VBB stays with accessibility.cloud: the feeds that duplicate it (`bvg`,
//    `brokenlifts`) are dropped wholesale (EXCLUDED_SOURCES), and DB records
//    whose FaSta equipment number a *searchable* local record already carries
//    are dropped per record (`excludingKnown`).
// 3. Honest status: the docs' freshness rule — a record whose feed was last
//    seen more than FRESHNESS_WINDOW ago reads "unbekannt" even when it says
//    ACTIVE; INACTIVE stays "defekt".
object AufzugsstatusClient {
    const val HOST = "https://aufzuege.ploetzlich-querschnitt.de"
    const val LIFTS = "lifts"
    const val NEAR = "stations/near"
    // /lifts matches the station name as a substring and caps at 1000; 300
    // records is plenty for a query that narrows to a station.
    const val SEARCH_LIMIT = 300
    // Below this the substring match returns half the country.
    const val MINIMUM_QUERY_LENGTH = 3

    // The docs' interpretation of `last_seen`: ≤ 15 min the state holds.
    val FRESHNESS_WINDOW: Duration = Duration.ofMinutes(15)

    // Feeds this client never surfaces: `bvg` and `brokenlifts` cover the
    // VBB, which transit.accessibility.cloud serves; `osm-at`, `osm-ch` and
    // `wienogd` are OpenStreetMap inventories without a live feed, every
    // record UNKNOWN.
    val EXCLUDED_SOURCES: Set<String> = setOf("bvg", "brokenlifts", "osm-at", "osm-ch", "wienogd")

    // The lookup radius sent to the server: the device coordinate is
    // coarsened to two decimals first (`coarsened`, a ~1.1 km × 0.7 km
    // cell), so the request radius covers the walking radius plus the
    // half-cell diagonal (~0.65 km); the true walking-distance cut is then
    // applied on the device, from the lifts' own coordinates.
    const val NEARBY_REQUEST_RADIUS_KM = 2.0
    const val NEARBY_REQUEST_LIMIT = 20

    private val idPattern = Regex("^[a-z][a-z0-9-]*:\\S+$")
    private val placeholderPattern = Regex("^Bahnhof \\d+$")

    // "source:anything" — the API's id scheme. Numeric accessibility.cloud
    // ids, Mongo ids and the pre-migration seed ids ("fasta-123") carry no
    // colon.
    fun isNationalId(id: String): Boolean = idPattern.matches(id)

    // MARK: DTOs

    @Serializable
    data class Lift(
        val id: String,
        val source: String,
        @SerialName("source_id") val sourceId: String? = null,
        @SerialName("station_number") val stationNumber: Int? = null,
        @SerialName("station_name") val stationName: String? = null,
        val description: String? = null,
        @SerialName("lat") val latitude: Double? = null,
        @SerialName("lon") val longitude: Double? = null,
        val operator: String? = null,
        val state: String? = null,
        @SerialName("state_explanation") val stateExplanation: String? = null,
        @SerialName("last_seen") val lastSeen: String? = null,
    )

    @Serializable
    data class NearbyStation(
        val name: String,
        @SerialName("station_number") val stationNumber: Int? = null,
        val lifts: Int = 0,
        @SerialName("lat") val latitude: Double,
        @SerialName("lon") val longitude: Double,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // MARK: Pure parsing and mapping (unit-tested)

    // null = not a lifts list (decode failure); the caller treats it as a
    // failed request.
    fun parseLifts(data: String): List<Lift>? =
        runCatching { json.decodeFromString<List<Lift>>(data) }.getOrNull()

    fun parseLift(data: String): Lift? =
        runCatching { json.decodeFromString<Lift>(data) }.getOrNull()

    fun parseNearbyStations(data: String): List<NearbyStation>? =
        runCatching { json.decodeFromString<List<NearbyStation>>(data) }.getOrNull()

    // Lifts → catalog records, filtered to what Hissi shows: excluded feeds
    // and nameless stations (the API fills `station` with "Bahnhof 962"
    // there) are dropped. Every record is region National.
    fun equipment(lifts: List<Lift>, now: Instant = Instant.now()): List<AccessibilityCloudClient.Equipment> =
        lifts.mapNotNull { lift ->
            if (lift.source in EXCLUDED_SOURCES) return@mapNotNull null
            val stationName = lift.stationName?.trim().orEmpty()
            if (stationName.isEmpty()) return@mapNotNull null
            val lastSeen = AccessibilityCloudClient.date(lift.lastSeen)
            val isWorking = isWorking(lift.state, lastSeen, now)
            // Only DB records carry the FaSta equipment number — the key the
            // local catalog (and its seed) also knows. Other feeds' ids live
            // in their own namespaces and could collide numerically.
            val inventoryId = if (lift.source == "fasta") lift.sourceId else null
            AccessibilityCloudClient.Equipment(
                id = lift.id,
                inventoryId = inventoryId,
                stationId = lift.stationNumber?.toString() ?: "",
                stationName = stationName,
                description = lift.description ?: "",
                isWorking = isWorking,
                lastUpdateEpochMillis = lastSeen?.toEpochMilli(),
                sourceName = sourceLabel(lift.source),
                organizationName = lift.operator ?: "",
                // "available" on a working lift says nothing; a broken or
                // unknown one explains itself.
                stateExplanation = if (isWorking == true) null else lift.stateExplanation?.takeIf { it.isNotEmpty() },
                latitude = lift.latitude,
                longitude = lift.longitude,
                fastaEquipmentNumber = inventoryId?.toIntOrNull(),
                region = TransitRegion.National,
            )
        }

    // ACTIVE holds only while the feed is fresh; INACTIVE is believed
    // regardless (a stale "defekt" is the safe reading); anything else —
    // UNKNOWN, a new word, no last_seen at all — is unknown, never working.
    fun isWorking(state: String?, lastSeen: Instant?, now: Instant = Instant.now()): Boolean? =
        when (state?.uppercase()) {
            "INACTIVE" -> false
            "ACTIVE" -> if (lastSeen != null && Duration.between(lastSeen, now) <= FRESHNESS_WINDOW) true else null
            else -> null
        }

    // What the detail's "Quelle" row says. DB FaSta keeps the spelling the
    // seed uses (TransitNetwork.classify keys on it).
    fun sourceLabel(source: String): String = when (source) {
        "fasta" -> "DB FaSta"
        "vagn" -> "VAG Nürnberg"
        else -> source.uppercase()
    }

    // FaSta equipment numbers the local catalog answers for *and* can show:
    // records without a station name are invisible in search and nearby, so
    // they must not shadow their national counterpart.
    fun knownInventoryNumbers(catalog: List<AccessibilityCloudClient.Equipment>): Set<String> =
        catalog.mapNotNull { record ->
            if (record.stationName.isEmpty()) null
            else record.inventoryId ?: record.fastaEquipmentNumber?.toString()
        }.toSet()

    // Drops the national records the local catalog already shows (matched
    // on the FaSta equipment number).
    fun excludingKnown(
        equipment: List<AccessibilityCloudClient.Equipment>,
        knownInventoryNumbers: Set<String>,
    ): List<AccessibilityCloudClient.Equipment> {
        if (knownInventoryNumbers.isEmpty()) return equipment
        return equipment.filter { record ->
            val inventoryId = record.inventoryId ?: return@filter true
            inventoryId !in knownInventoryNumbers
        }
    }

    // Two decimals: the cell the server learns is about a kilometre wide.
    fun coarsened(coordinate: Double): Double = (coordinate * 100).roundToLong() / 100.0

    // The placeholder the API uses where a station has no name.
    fun isPlaceholderName(name: String): Boolean = placeholderPattern.matches(name)

    // Lifts answered for a nearby station, with the station's own coordinate
    // standing in for lifts that carry none (HVV, MVG), so the station is
    // placed and every lift counts. A name query is a substring match, so
    // only the station asked for is kept.
    fun placed(lifts: List<Lift>, station: NearbyStation): List<Lift> =
        lifts.mapNotNull { lift ->
            if (!lift.stationName.equals(station.name, ignoreCase = true)) return@mapNotNull null
            if (lift.latitude != null && lift.longitude != null) lift
            else lift.copy(latitude = station.latitude, longitude = station.longitude)
        }
}
