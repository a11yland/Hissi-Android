package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val nationalJSON = """
{
 "generatedAt": "2026-11-20",
 "source": "Aufzugsstatus API",
 "stations": [
  {"stationNumber": 2514, "name": "Hamburg Hbf", "latitude": 53.55316, "longitude": 10.00705,
   "lifts": [10028019, 10028022, 10804843, 10804989]},
  {"stationNumber": 4935, "name": "Pinneberg", "latitude": 53.65503, "longitude": 9.7976,
   "lifts": [10594142, 10594143, 10594156]},
  {"stationNumber": 962, "name": "Buir", "lifts": [10818160]}
 ]
}
"""

// Ported 1:1 from HissiTests/Tests/NationalStationsTests.swift: the bundled
// national station list and the overlay step that names the nameless
// nationwide DB records of accessibility.cloud from it.
class NationalStationsTest {
    private val stations = NationalStations.parse(nationalJSON)

    @Test
    fun parsesStationsAndIndexesTheirLifts() {
        assertContentEquals(listOf(2514, 4935, 962), stations.map { it.stationNumber })
        assertEquals(4, stations[0].lifts.size)
        assertNull(stations[2].latitude)
        val byLift = NationalStations.byLift(stations)
        assertEquals("Hamburg Hbf", byLift["10804843"]?.name)
        assertEquals(4935, byLift["10594156"]?.stationNumber)
        assertNull(byLift["1"])
        assertTrue(NationalStations.parse("nonsense").isEmpty())
    }

    private fun nameless(id: String, inventoryId: String, latitude: Double? = null) = AccessibilityCloudClient.Equipment(
        id = id, inventoryId = inventoryId, stationId = "", stationName = "",
        description = "zu Gleis 1/2 (S-Bahn)", isWorking = true,
        latitude = latitude, longitude = latitude?.let { 10.0077 },
    )

    @Test
    fun namesNamelessDBRecordsFromTheList() {
        val byLift = NationalStations.byLift(stations)
        val named = EquipmentCatalog.nationalNamed(nameless("7000", "10804843"), byLift)
        assertEquals("Hamburg Hbf", named.stationName)
        assertEquals("2514", named.stationId)
        assertEquals(TransitRegion.National, named.region)
        assertEquals("DB FaSta", named.sourceName)
        assertEquals("DB InfraGO", named.organizationName)
        assertEquals(10_804_843, named.fastaEquipmentNumber)
        // The station's coordinate only where the record carries none.
        assertEquals(53.55316, named.latitude)
        val placed = EquipmentCatalog.nationalNamed(nameless("7001", "10804989", latitude = 53.5531808), byLift)
        assertEquals(53.5531808, placed.latitude)
        // The record's own identity and status are untouched.
        assertEquals("7000", named.id)
        assertEquals(true, named.isWorking)
        assertEquals("zu Gleis 1/2 (S-Bahn)", named.description)
    }

    @Test
    fun leavesNamedRecordsAndUnknownNumbersAlone() {
        val byLift = NationalStations.byLift(stations)
        val berlin = AccessibilityCloudClient.Equipment(
            id = "6225", inventoryId = "10804843", stationId = "900077106", stationName = "Sonnenallee (Berlin)",
            description = "zu Gleis 1/2", isWorking = true, region = TransitRegion.Berlin,
        )
        assertEquals(berlin, EquipmentCatalog.nationalNamed(berlin, byLift))
        val unknown = nameless("7002", "99")
        assertEquals(unknown, EquipmentCatalog.nationalNamed(unknown, byLift))
        assertEquals(unknown, EquipmentCatalog.nationalNamed(unknown, emptyMap()))
    }

    @Test
    fun overlaidCatalogNamesAndTheSearchFindsIt() {
        val live = listOf(nameless("7000", "10804843"), nameless("7003", "10594156"))
        val catalog = EquipmentCatalog.overlaid(live = live, seed = emptyList(), national = stations)
        assertContentEquals(listOf("Hamburg Hbf", "Pinneberg"), catalog.map { it.stationName })
        assertTrue(catalog.all { it.region == TransitRegion.National })
        assertEquals(setOf("10804843", "10594156"), AufzugsstatusClient.knownInventoryNumbers(catalog))
        val grouped = SearchGrouping.group(catalog)
        assertContentEquals(listOf(TransitRegion.National), grouped.map { it.region })
        assertContentEquals(listOf("Hamburg Hbf", "Pinneberg"), grouped[0].stations.map { it.station.name })
    }

    // The nearby gate: the national API is asked only while no VBB station is
    // in walking distance — a local DB station beyond the VBB is no reason not
    // to ask (its city-operator neighbours are not local).
    @Test
    fun nationalLookupOnlyWithoutAVBBStationInReach() {
        val hamburg = EquipmentCatalog.nationalNamed(nameless("7000", "10804843"), NationalStations.byLift(stations))
        val berlin = AccessibilityCloudClient.Equipment(
            id = "6225", stationId = "900077106", stationName = "Sonnenallee (Berlin)",
            description = "zu Gleis 1/2", isWorking = true,
            latitude = 52.4727, longitude = 13.4555, region = TransitRegion.Berlin,
        )
        // Seed-only VBB record without a region counts as VBB too.
        val brandenburg = AccessibilityCloudClient.Equipment(
            id = "fasta-1", stationId = "1", stationName = "Waßmannsdorf",
            description = "zu Gleis 2", latitude = 52.3645, longitude = 13.4651,
        )
        val catalog = listOf(hamburg, berlin, brandenburg)
        assertTrue(AufzugsstatusClient.needsNationalLookup(53.5531, 10.0065, catalog))
        assertFalse(AufzugsstatusClient.needsNationalLookup(52.4727, 13.4555, catalog))
        assertFalse(AufzugsstatusClient.needsNationalLookup(52.3645, 13.4651, catalog))
        assertTrue(AufzugsstatusClient.needsNationalLookup(50.9, 6.95, catalog))
        assertTrue(AufzugsstatusClient.needsNationalLookup(52.4727, 13.4555, emptyList()))
    }
}
