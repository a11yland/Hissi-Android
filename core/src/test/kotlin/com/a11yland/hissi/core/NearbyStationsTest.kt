package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/NearbyStationsTests.swift — the
// on-device sort behind the "In der Nähe" suggestions, and the status
// roll-up each row shows.
class NearbyStationsTest {
    private fun equipment(
        station: String,
        latitude: Double?,
        longitude: Double?,
        id: String? = null,
        isWorking: Boolean? = null,
    ) = AccessibilityCloudClient.Equipment(
        id = id ?: "$station-${latitude ?: 0.0}",
        stationId = "1",
        stationName = station,
        description = "Aufzug",
        isWorking = isWorking,
        latitude = latitude,
        longitude = longitude,
    )

    // Alexanderplatz → Hackescher Markt is a known ~800 m hop; haversine
    // must land in that ballpark (city-scale accuracy is what matters).
    @Test
    fun distanceIsMeterAccurateAtCityScale() {
        val meters = NearbyStations.distanceMeters(52.5219, 13.4132, 52.5225, 13.4021)
        assertTrue(meters > 600 && meters < 900)
    }

    @Test
    fun sortsStationsByTheirClosestElevator() {
        val catalog = listOf(
            equipment("S Hackescher Markt", 52.5225, 13.4021),
            // Two elevators, the near one decides the station's distance.
            equipment("S+U Alexanderplatz", 52.5219, 13.4132),
            equipment("S+U Alexanderplatz", 52.5210, 13.4150),
            equipment("S+U Jannowitzbrücke", 52.5152, 13.4185),
        )
        // Standing on Alexanderplatz:
        val nearest = NearbyStations.nearest(latitude = 52.5219, longitude = 13.4132, catalog = catalog)
        assertContentEquals(
            listOf("S+U Alexanderplatz", "S Hackescher Markt", "S+U Jannowitzbrücke"),
            nearest.map { it.name },
        )
        assertEquals(0.0, nearest.first().distanceMeters)
    }

    @Test
    fun skipsRecordsWithoutCoordinatesAndCapsAtLimit() {
        val catalog = listOf(
            equipment("Ohne Koordinaten", null, null),
            equipment("A", 52.52, 13.41),
            equipment("B", 52.523, 13.41),
            equipment("C", 52.526, 13.41),
        )
        val nearest = NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog, limit = 2)
        assertContentEquals(listOf("A", "B"), nearest.map { it.name })
        assertFalse(nearest.any { it.name == "Ohne Koordinaten" })
    }

    // "Nearby" is what the user can walk to: a station beyond the radius stays
    // out even when it is the closest one there is, and the station's closest
    // elevator is what has to be within reach.
    @Test
    fun keepsOnlyStationsWithinWalkingDistance() {
        val catalog = listOf(
            // ~800 m north: in.
            equipment("S Nah", 52.5272, 13.41),
            // ~1.6 km north: out.
            equipment("S Fern", 52.5344, 13.41),
            // Two elevators, the near one (~500 m) puts the station in reach.
            equipment("S+U Zwei", 52.5245, 13.41),
            equipment("S+U Zwei", 52.54, 13.41),
        )
        val nearest = NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog)
        assertContentEquals(listOf("S+U Zwei", "S Nah"), nearest.map { it.name })
        assertTrue(nearest.all { it.distanceMeters <= NearbyStations.WALKING_RADIUS_METERS })
    }

    // Nothing within reach yields nothing — the nearest station in the region
    // is not a walk, and the UI says so instead of listing it.
    @Test
    fun nothingWithinWalkingDistanceIsEmpty() {
        val catalog = listOf(equipment("S Fern", 52.5344, 13.41))
        assertTrue(NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog).isEmpty())
        // The radius is a parameter, not a hidden constant.
        assertContentEquals(
            listOf("S Fern"),
            NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog, withinMeters = 2_000.0)
                .map { it.name },
        )
    }

    // Broken outranks unknown outranks all-clear, and the ratio is what the
    // row says: "1 von 3" leaves the user an alternative.
    @Test
    fun brokenOutranksUnknownAndShowsTheRatio() {
        val catalog = listOf(
            equipment("S+U Pankow", 52.56, 13.41, id = "1", isWorking = false),
            equipment("S+U Pankow", 52.56, 13.41, id = "2", isWorking = null),
            equipment("S+U Pankow", 52.56, 13.41, id = "3", isWorking = true),
        )
        val station = NearbyStations.nearest(latitude = 52.56, longitude = 13.41, catalog = catalog)[0]
        assertEquals(3, station.total)
        assertEquals(1, station.brokenCount)
        assertEquals(1, station.unknownCount)
        assertEquals(ElevatorStatus.Broken, station.status)
        assertEquals("1 von 3 außer Betrieb", station.statusLabel)
    }

    // An elevator the seed has no coordinates for is still one of the lifts at
    // that station — it can't place the station, but it counts.
    @Test
    fun elevatorsWithoutCoordinatesStillCountTowardsTheStatus() {
        val catalog = listOf(
            equipment("U Spittelmarkt", 52.51, 13.40, id = "1", isWorking = true),
            equipment("U Spittelmarkt", null, null, id = "2", isWorking = false),
        )
        val station = NearbyStations.nearest(latitude = 52.51, longitude = 13.40, catalog = catalog)[0]
        assertEquals(2, station.total)
        assertEquals("1 von 2 außer Betrieb", station.statusLabel)
    }

    // One lift, no ratio to give — "1 von 1 außer Betrieb" reads absurd.
    @Test
    fun singleElevatorStationSpeaksTheElevatorVocabulary() {
        val catalog = listOf(equipment("S Grünau", 52.41, 13.58, id = "1", isWorking = false))
        val station = NearbyStations.nearest(latitude = 52.41, longitude = 13.58, catalog = catalog)[0]
        assertEquals(ElevatorStatus.Broken, station.status)
        assertEquals(ElevatorStatus.Broken.label, station.statusLabel)
    }

    // Statuses the catalog doesn't know (seed preview, offline) must never
    // round up to an all-clear.
    @Test
    fun unknownStatusesNeverReadAsAllClear() {
        val allUnknown = listOf(
            equipment("S Fredersdorf", 52.53, 13.75, id = "1"),
            equipment("S Fredersdorf", 52.53, 13.75, id = "2"),
        )
        val station = NearbyStations.nearest(latitude = 52.53, longitude = 13.75, catalog = allUnknown)[0]
        assertEquals(ElevatorStatus.Unknown, station.status)
        assertEquals(ElevatorStatus.Unknown.label, station.statusLabel)

        val partial = allUnknown + equipment("S Fredersdorf", 52.53, 13.75, id = "3", isWorking = true)
        val mixed = NearbyStations.nearest(latitude = 52.53, longitude = 13.75, catalog = partial)[0]
        assertEquals(ElevatorStatus.Unknown, mixed.status)
        assertEquals("2 von 3 unbekannt", mixed.statusLabel)
    }

    @Test
    fun allWorkingStationSaysSo() {
        val catalog = listOf(
            equipment("S Ostkreuz", 52.50, 13.46, id = "1", isWorking = true),
            equipment("S Ostkreuz", 52.50, 13.46, id = "2", isWorking = true),
        )
        val station = NearbyStations.nearest(latitude = 52.50, longitude = 13.46, catalog = catalog)[0]
        assertEquals(ElevatorStatus.Working, station.status)
        assertEquals("Alle in Betrieb", station.statusLabel)
    }

    @Test
    fun elevatorIdsCoverEveryShownStation() {
        val catalog = listOf(
            equipment("A", 52.52, 13.41, id = "1"),
            equipment("A", null, null, id = "2"),
            equipment("B", 52.523, 13.41, id = "3"),
            equipment("C", 52.526, 13.41, id = "4"),
        )
        val nearest = NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog, limit = 2)
        assertEquals(setOf("1", "2", "3"), NearbyStations.elevatorIds(nearest))
    }

    // The refresh may answer for only some of the ids (a failed fetch degrades
    // to the seed overlay). Those it doesn't answer keep what they had — and
    // nothing about the list's order or distances may move.
    @Test
    fun restatedUpdatesStatusesWithoutMovingRows() {
        val catalog = listOf(
            equipment("A", 52.52, 13.41, id = "1", isWorking = true),
            equipment("A", 52.52, 13.41, id = "2", isWorking = true),
            equipment("B", 52.523, 13.41, id = "3", isWorking = true),
        )
        val before = NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog)
        assertContentEquals(listOf("A", "B"), before.map { it.name })

        // "1" broke since the snapshot, "3" is still working, "2" unanswered.
        val fresh = mapOf(
            "1" to equipment("A", 52.52, 13.41, id = "1", isWorking = false),
            "3" to equipment("B", 52.523, 13.41, id = "3", isWorking = true),
        )
        val after = NearbyStations.restated(before, fresh)

        assertContentEquals(before.map { it.name }, after.map { it.name })
        assertContentEquals(before.map { it.distanceMeters }, after.map { it.distanceMeters })
        assertEquals(ElevatorStatus.Broken, after[0].status)
        assertEquals("1 von 2 außer Betrieb", after[0].statusLabel)
        // Unanswered "2" kept its working status instead of falling to unknown.
        assertEquals(0, after[0].unknownCount)
        assertEquals(ElevatorStatus.Working, after[1].status)
    }

    // A bridged seed favorite resolves to a record with a different (live) id;
    // the station must keep addressing it by the id it asked for, or the next
    // refresh would no longer find it.
    @Test
    fun restatedKeepsTheRequestedIds() {
        val catalog = listOf(equipment("A", 52.52, 13.41, id = "fasta-4711"))
        val before = NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog)
        val fresh = mapOf(
            "fasta-4711" to equipment("A", 52.52, 13.41, id = "90210", isWorking = false),
        )
        val after = NearbyStations.restated(before, fresh)
        assertContentEquals(listOf("fasta-4711"), after[0].elevators.map { it.id })
        assertEquals(ElevatorStatus.Broken, after[0].status)
    }

    @Test
    fun restatedWithNothingFreshLeavesTheStationsAlone() {
        val catalog = listOf(equipment("A", 52.52, 13.41, id = "1", isWorking = true))
        val before = NearbyStations.nearest(latitude = 52.52, longitude = 13.41, catalog = catalog)
        assertContentEquals(before, NearbyStations.restated(before, emptyMap()))
    }
}
