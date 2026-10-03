package com.a11yland.hissi.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Distance sort behind the "In der Nähe" suggestions: stations from the
// catalog within walking distance, nearest first, each with the status of its
// elevators. Pure
// (haversine, no platform location APIs); the app supplies the user
// coordinate. Ported from `Shared/NearbyStations.swift`.
//
// Distance and status read different slices of the same station: coordinates
// come from the seed overlay, so a station without any stays out entirely,
// but an elevator without coordinates still counts towards the station's
// status — it is one of the lifts the user would take.
object NearbyStations {
    // "Nearby" means reachable on foot: the suggestion is the station the user
    // could walk to now, not the nearest one in the region — in Brandenburg
    // the nearest station can be a car ride away, and listing it would only
    // pretend otherwise. One kilometre is a 12–15 minute walk. The UI names
    // the radius so an empty list reads as "nothing within reach", not as a
    // failure.
    const val WALKING_RADIUS_METERS = 1_000.0

    data class Station(
        val name: String,
        val distanceMeters: Double,
        // Every elevator of the station, in catalog order. Carried per id (not
        // as counts) so a partial status refresh can replace what it resolved
        // and leave the rest standing — see `restated`.
        val elevators: List<Elevator>,
    ) {
        data class Elevator(val id: String, val isWorking: Boolean?)

        val id: String get() = name

        val total: Int get() = elevators.size
        val brokenCount: Int get() = elevators.count { it.isWorking == false }
        val unknownCount: Int get() = elevators.count { it.isWorking == null }

        // Same precedence as the favorites verdict (ElevatorSummary): broken
        // outranks unknown outranks all-clear, so a station never reads as
        // working while one of its lifts is out.
        val status: ElevatorStatus
            get() = when {
                // A station without elevators can only be a construction
                // error — "Alle in Betrieb" would be a false all-clear.
                elevators.isEmpty() -> ElevatorStatus.Unknown
                brokenCount > 0 -> ElevatorStatus.Broken
                unknownCount > 0 -> ElevatorStatus.Unknown
                else -> ElevatorStatus.Working
            }

        // What the row says next to the icon. A station is not an elevator:
        // the ratio is the useful part ("1 von 3" leaves an alternative, "1 von
        // 1" does not), so the counts appear as soon as there is more than one
        // lift. Never claims an all-clear while something is unknown.
        val statusLabel: String
            get() = when {
                total <= 1 -> status.label
                brokenCount > 0 -> "$brokenCount von $total außer Betrieb"
                unknownCount == total -> ElevatorStatus.Unknown.label
                unknownCount > 0 -> "$unknownCount von $total unbekannt"
                else -> "Alle in Betrieb"
            }
    }

    fun nearest(
        latitude: Double,
        longitude: Double,
        catalog: List<AccessibilityCloudClient.Equipment>,
        limit: Int = 5,
        withinMeters: Double = WALKING_RADIUS_METERS,
    ): List<Station> {
        val named = catalog.filter { it.stationName.isNotEmpty() }
        // Grouped by the plain station name — the same key SearchGrouping
        // uses, so tapping a row lists exactly the elevators counted here.
        return named.groupBy { it.stationName }
            .mapNotNull { (name, records) ->
                val distances = records.mapNotNull { record ->
                    val recordLatitude = record.latitude
                    val recordLongitude = record.longitude
                    if (recordLatitude == null || recordLongitude == null) {
                        null
                    } else {
                        distanceMeters(latitude, longitude, recordLatitude, recordLongitude)
                    }
                }
                // A station is as close as its closest elevator; one without
                // any coordinates at all can't be placed and stays out, and
                // so does one beyond walking distance.
                val closest = distances.minOrNull()
                if (closest == null || closest > withinMeters) {
                    null
                } else {
                    Station(
                        name = name,
                        distanceMeters = closest,
                        elevators = records.map { Station.Elevator(it.id, it.isWorking) },
                    )
                }
            }
            // Name breaks distance ties so the order is deterministic
            // (grouping isn't).
            .sortedWith(compareBy({ it.distanceMeters }, { it.name }))
            .take(limit)
    }

    // Every elevator id the given stations are made of — the batch a status
    // refresh asks for (EquipmentCatalog.equipment(for)).
    fun elevatorIds(stations: List<Station>): Set<String> =
        stations.flatMap { station -> station.elevators.map { it.id } }.toSet()

    // Folds freshly fetched records into the stations: only statuses change,
    // never names, distances or order — the list must not reshuffle under the
    // user's finger while a request lands. Elevators the refresh didn't
    // resolve keep the status they had (a partial answer beats downgrading
    // them all to unknown). Keyed by the requested id — a bridged record may
    // carry a different id of its own, and adopting it here would break the
    // next fold.
    fun restated(
        stations: List<Station>,
        fresh: Map<String, AccessibilityCloudClient.Equipment>,
    ): List<Station> {
        if (fresh.isEmpty()) return stations
        return stations.map { station ->
            station.copy(
                elevators = station.elevators.map { elevator ->
                    val record = fresh[elevator.id] ?: return@map elevator
                    Station.Elevator(elevator.id, record.isWorking)
                },
            )
        }
    }

    // Haversine over the mean earth radius — meter-accurate at city scale.
    fun distanceMeters(
        latitude1: Double, longitude1: Double,
        latitude2: Double, longitude2: Double,
    ): Double {
        val radius = 6_371_000.0
        val deltaLatitude = (latitude2 - latitude1) * Math.PI / 180
        val deltaLongitude = (longitude2 - longitude1) * Math.PI / 180
        val a = sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(latitude1 * Math.PI / 180) * cos(latitude2 * Math.PI / 180) *
            sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
