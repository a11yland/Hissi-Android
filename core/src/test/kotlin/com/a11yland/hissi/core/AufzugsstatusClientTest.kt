package com.a11yland.hissi.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/AufzugsstatusClientTests.swift, against
// the same fixtures: parsing the /lifts and /stations/near responses, the
// mapping onto catalog records, the freshness rule, the id namespace and the
// dedup against the local catalog.
class AufzugsstatusClientTest {
    // Shortly after the fixtures' fresh `last_seen` (18:08:28Z), well past
    // the stale one (17:30Z).
    private val now: Instant = Instant.parse("2026-11-20T18:10:00Z")

    private fun records(): List<AccessibilityCloudClient.Equipment> {
        val lifts = assertNotNull(AufzugsstatusClient.parseLifts(fixtureData("aufzugsstatus_lifts")))
        assertEquals(10, lifts.size)
        return AufzugsstatusClient.equipment(lifts, now)
    }

    @Test
    fun mapsLiftsOntoCatalogRecords() {
        val records = records()
        val hamburg = assertNotNull(records.firstOrNull { it.id == "fasta:10804843" })
        assertEquals("Hamburg Hbf", hamburg.stationName)
        assertEquals("2514", hamburg.stationId)
        assertEquals("zu Gleis 1/2 (S-Bahn)", hamburg.description)
        assertEquals(true, hamburg.isWorking)
        // A working lift's "available" is no disruption reason.
        assertNull(hamburg.stateExplanation)
        assertEquals("DB FaSta", hamburg.sourceName)
        assertEquals("DB InfraGO", hamburg.organizationName)
        // Only DB records carry the FaSta equipment number, the key the
        // local catalog knows too.
        assertEquals("10804843", hamburg.inventoryId)
        assertEquals(10_804_843, hamburg.fastaEquipmentNumber)
        assertEquals(53.5532639, hamburg.latitude)
        assertEquals(Instant.parse("2026-11-20T18:08:28Z"), hamburg.lastUpdate)
        assertEquals(TransitRegion.National, hamburg.region)

        val kvb = assertNotNull(records.firstOrNull { it.id == "kvb:034-01" })
        assertTrue(kvb.stationId.isEmpty())
        assertNull(kvb.inventoryId)
        assertNull(kvb.fastaEquipmentNumber)
        assertEquals("KVB", kvb.sourceName)
        assertEquals("KVB", kvb.organizationName)
    }

    @Test
    fun dropsExcludedFeedsAndNamelessStations() {
        val ids = records().map { it.id }.toSet()
        // VBB feeds (accessibility.cloud's territory) and the OSM inventories
        // without a live feed stay out, so does the DB record whose station
        // has no name ("Bahnhof 962").
        assertFalse(ids.any { it.startsWith("bvg:") })
        assertFalse(ids.any { it.startsWith("osm-ch:") })
        assertFalse("fasta:10818160" in ids)
        assertEquals(7, ids.size)
    }

    // The docs' freshness rule: ACTIVE holds only while the feed reported
    // within 15 minutes; INACTIVE is believed either way; UNKNOWN stays
    // unknown and keeps its explanation.
    @Test
    fun appliesTheFreshnessRule() {
        val byId = records().associateBy { it.id }
        assertEquals(true, byId["fasta:10804843"]?.isWorking)
        // ACTIVE, last seen 40 minutes ago: the feed went quiet, not the lift.
        assertNull(byId.getValue("fasta:10804989").isWorking)
        assertEquals(false, byId["fasta:10455451"]?.isWorking)
        assertEquals("not available", byId["fasta:10455451"]?.stateExplanation)
        assertEquals(false, byId["hvv:Master:11950-E"]?.isWorking)
        assertNull(byId.getValue("hvv:Master:11950-E").stateExplanation)
        assertNull(byId.getValue("fasta:10028019").isWorking)
        assertEquals("under construction", byId["fasta:10028019"]?.stateExplanation)
    }

    @Test
    fun freshnessRuleEdges() {
        val seen = now.minus(AufzugsstatusClient.FRESHNESS_WINDOW)
        assertEquals(true, AufzugsstatusClient.isWorking("ACTIVE", seen, now))
        assertNull(AufzugsstatusClient.isWorking("ACTIVE", seen.minusSeconds(1), now))
        assertEquals(true, AufzugsstatusClient.isWorking("active", now, now))
        assertNull(AufzugsstatusClient.isWorking("ACTIVE", null, now))
        assertEquals(false, AufzugsstatusClient.isWorking("INACTIVE", null, now))
        assertNull(AufzugsstatusClient.isWorking("UNKNOWN", now, now))
        // A new word must read "unbekannt", never "defekt" or "in Betrieb".
        assertNull(AufzugsstatusClient.isWorking("PARTIAL", now, now))
        assertNull(AufzugsstatusClient.isWorking(null, now, now))
    }

    // "source:…" is the API's namespace; everything the app ever stored as a
    // favorite id before (numeric, Mongo, seed ids with hyphens) is not.
    @Test
    fun tellsNationalIdsFromLocalOnes() {
        assertTrue(AufzugsstatusClient.isNationalId("fasta:10804843"))
        assertTrue(AufzugsstatusClient.isNationalId("hvv:Master:11950-E"))
        assertTrue(AufzugsstatusClient.isNationalId("kvb:034-01"))
        assertTrue(AufzugsstatusClient.isNationalId("osm-ch:3858714670"))
        assertFalse(AufzugsstatusClient.isNationalId("6225"))
        assertFalse(AufzugsstatusClient.isNationalId("fasta-10466003"))
        assertFalse(AufzugsstatusClient.isNationalId("brokenlifts-900009103-0"))
        assertFalse(AufzugsstatusClient.isNationalId("z87ZFWJs5aB2seuYf"))
        assertFalse(AufzugsstatusClient.isNationalId(""))
    }

    // The local catalog shadows a national DB record only where it can show
    // it: a record without a station name is invisible in search and nearby.
    @Test
    fun dedupsAgainstTheSearchableLocalCatalog() {
        val local = listOf(
            AccessibilityCloudClient.Equipment(
                id = "6225", inventoryId = "10455451", stationId = "900077106",
                stationName = "Sonnenallee (Berlin)", description = "zu Gleis 1/2", isWorking = true,
            ),
            // Nationwide DB record the platform carries without a station.
            AccessibilityCloudClient.Equipment(
                id = "7000", inventoryId = "10804843", stationId = "",
                stationName = "", description = "zu Gleis 1/2 (S-Bahn)", isWorking = true,
            ),
            // Seed-only record: the number lives in fastaEquipmentNumber.
            AccessibilityCloudClient.Equipment(
                id = "fasta-10594156", stationId = "4935", stationName = "Pinneberg",
                description = "PU Ausgang Süd", fastaEquipmentNumber = 10_594_156,
            ),
        )
        val known = AufzugsstatusClient.knownInventoryNumbers(local)
        assertEquals(setOf("10455451", "10594156"), known)

        val kept = AufzugsstatusClient.excludingKnown(records(), known)
        val ids = kept.map { it.id }.toSet()
        assertFalse("fasta:10455451" in ids)
        assertTrue("fasta:10804843" in ids)
        // Non-DB records carry no inventory id and are never shadowed.
        assertTrue("kvb:034-01" in ids)
        assertTrue("hvv:Master:11950-A" in ids)
        assertEquals(kept.size, AufzugsstatusClient.excludingKnown(kept, emptySet()).size)
    }

    @Test
    fun sourceLabels() {
        assertEquals("DB FaSta", AufzugsstatusClient.sourceLabel("fasta"))
        assertEquals("HVV", AufzugsstatusClient.sourceLabel("hvv"))
        assertEquals("VAG Nürnberg", AufzugsstatusClient.sourceLabel("vagn"))
    }

    // MARK: Nearby

    @Test
    fun parsesNearbyStationsAndSpotsThePlaceholder() {
        val stations = assertNotNull(AufzugsstatusClient.parseNearbyStations(fixtureData("aufzugsstatus_near")))
        assertEquals(6, stations.size)
        assertEquals("Hamburg Hbf", stations[0].name)
        assertEquals(2514, stations[0].stationNumber)
        assertEquals(8, stations[0].lifts)
        // The API lists the nameless lifts of a station as "Bahnhof <n>"
        // next to the station itself.
        assertTrue(AufzugsstatusClient.isPlaceholderName(stations[1].name))
        assertFalse(AufzugsstatusClient.isPlaceholderName("Bahnhof Zoo"))
        assertEquals(5, stations.count { !AufzugsstatusClient.isPlaceholderName(it.name) })
    }

    // Lifts answered for a station: a name query is a substring match, so
    // only the station asked for is kept, and lifts without coordinates
    // (HVV) take the station's so they are placed and counted.
    @Test
    fun placesLiftsAtTheirStation() {
        val lifts = assertNotNull(AufzugsstatusClient.parseLifts(fixtureData("aufzugsstatus_lifts")))
        val jungfernstieg = AufzugsstatusClient.NearbyStation(
            name = "Jungfernstieg", stationNumber = null, lifts = 5, latitude = 53.5527, longitude = 9.9935,
        )
        val placed = AufzugsstatusClient.placed(lifts, jungfernstieg)
        assertContentEquals(listOf("hvv:Master:11950-E", "hvv:Master:11950-A"), placed.map { it.id })
        assertTrue(placed.all { it.latitude == 53.5527 && it.longitude == 9.9935 })

        val hamburg = AufzugsstatusClient.NearbyStation(
            name = "hamburg hbf", stationNumber = 2514, lifts = 8, latitude = 53.5531, longitude = 10.007,
        )
        val own = AufzugsstatusClient.placed(lifts, hamburg)
        assertEquals(3, own.size)
        // Lifts with coordinates keep their own.
        assertEquals(53.5532639, own.first { it.id == "fasta:10804843" }.latitude)
    }

    // The nearby rows come out of the same sort as the local ones, over the
    // true device coordinate — so the cut at walking distance holds even
    // though the server was asked with a coarsened position and a wider
    // radius.
    @Test
    fun nearbyRowsFollowTheWalkingRadius() {
        val lifts = assertNotNull(AufzugsstatusClient.parseLifts(fixtureData("aufzugsstatus_lifts")))
        val equipment = AufzugsstatusClient.equipment(lifts, now)
        // Standing at Hamburg Hbf: Hamburg Hbf is in reach, Neumarkt (Köln),
        // Jungfernstieg (no coordinates) and Sonnenallee (Berlin) are not.
        val stations = NearbyStations.nearest(53.5531, 10.0065, equipment)
        assertContentEquals(listOf("Hamburg Hbf"), stations.map { it.name })
        val hamburg = stations.first()
        assertEquals(3, hamburg.total)
        // One ACTIVE, one stale ACTIVE, one UNKNOWN under construction.
        assertEquals(2, hamburg.unknownCount)
        assertEquals(ElevatorStatus.Unknown, hamburg.status)
    }

    @Test
    fun coarsensTheCoordinateToAKilometreCell() {
        assertEquals(53.55, AufzugsstatusClient.coarsened(53.55318))
        assertEquals(10.01, AufzugsstatusClient.coarsened(10.00616))
        assertEquals(0.0, AufzugsstatusClient.coarsened(-0.004))
        // The request radius covers the walking radius plus the rounding
        // error, so nothing in walking distance is cut server-side.
        val halfCellDiagonalMeters = NearbyStations.distanceMeters(53.55, 10.0, 53.555, 10.005)
        assertTrue(
            AufzugsstatusClient.NEARBY_REQUEST_RADIUS_KM * 1000 >=
                NearbyStations.WALKING_RADIUS_METERS + halfCellDiagonalMeters,
        )
    }
}
