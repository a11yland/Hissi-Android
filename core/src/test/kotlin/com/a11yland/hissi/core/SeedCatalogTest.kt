package com.a11yland.hissi.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/SeedCatalogTests.swift.

private val seedJSON = """
{
"version":2,
"generatedAt":"2026-11-21T20:00:00Z",
"elevators":[
 {"id":"6696","inventoryId":"5279","stationId":"900130002","stationName":"Pankow (Berlin)","stationModes":[1,2],"region":"berlin","description":"Vorhalle ↔ Bahnsteig U2","latitude":52.567,"longitude":13.412,"sourceName":"BVG","organizationName":"Berliner Verkehrsbetriebe (BVG)"},
 {"id":"6697","stationId":"900130002","stationName":"Pankow (Berlin)","stationModes":[1,2],"region":"berlin","description":"Vorhalle ↔ S-Bahnsteig","sourceName":"BVG","organizationName":"Berliner Verkehrsbetriebe (BVG)"},
 {"id":"3910","inventoryId":"10855569","stationId":"name:Dinslaken Bahnhof","stationName":"Dinslaken Bahnhof","stationModes":[],"region":"nordrhein-westfalen","description":"→ Bahnsteig 2","latitude":51.567681,"longitude":6.738101,"sourceName":"DB FaSta","organizationName":"DB InfraGO"},
 {"id":"4011","stationId":"name:Wernau (N)","stationName":"Wernau (N)","description":"→ Gleis 1"}
],
"stations":[
 {"stationId":"900130002","dhid":"de:11000:900130002","name":"Pankow (Berlin)","region":"berlin","latitude":52.5671,"longitude":13.4121},
 {"stationId":"name:Wernau (N)","name":"Wernau (N)","region":"baden-wuerttemberg","latitude":48.6890,"longitude":9.4160}
],
"legacyIds":{
 "fasta-10855569":"3910",
 "z87ZFWJs5aB2seuYf":"6696"
}
}
"""

class SeedCatalogTest {
    @Test
    fun parsesAllRecordsWithOptionalFields() {
        val records = SeedCatalog.parse(seedJSON)
        assertEquals(4, records.size)
        val pankow = records.first()
        assertEquals("6696", pankow.id)
        assertEquals("5279", pankow.inventoryId)
        assertEquals(setOf(TransitNetwork.SBahn, TransitNetwork.UBahn), pankow.networks)
        assertEquals(TransitRegion.Berlin, pankow.transitRegion)
        // Missing fields decode to their empty values.
        val bare = records.last()
        assertNull(bare.inventoryId)
        assertTrue(bare.stationModes.isEmpty())
        assertNull(bare.region)
        assertNull(bare.latitude)
        assertTrue(bare.sourceName.isEmpty())
    }

    @Test
    fun parsesStationsAndLegacyIds() {
        val stations = SeedCatalog.parseStations(seedJSON)
        assertEquals(listOf("900130002", "name:Wernau (N)"), stations.map { it.stationId })
        assertNull(stations[1].dhid)
        assertEquals(
            mapOf("fasta-10855569" to "3910", "z87ZFWJs5aB2seuYf" to "6696"),
            SeedCatalog.parseLegacyIds(seedJSON),
        )
    }

    @Test
    fun unparseableDataYieldsEmpty() {
        assertTrue(SeedCatalog.parse("nonsense").isEmpty())
        assertTrue(SeedCatalog.parseStations("{}").isEmpty())
        assertTrue(SeedCatalog.parseLegacyIds("{}").isEmpty())
    }

    // The bundled seed itself: live ids only, every record at a station, and
    // every station known to the coordinate list. Gradle runs the tests in
    // the module directory.
    @Test
    fun bundledSeedIsConsistent() {
        val data = File("../app/src/main/assets/seed-catalog.json").readText()
        val records = SeedCatalog.parse(data)
        val stations = SeedCatalog.parseStations(data).map { it.stationId }.toSet()
        assertTrue(records.size > 1000)
        assertTrue(records.all { it.id.toLongOrNull() != null })
        assertEquals(records.size, records.map { it.id }.toSet().size)
        assertTrue(records.all { it.stationName.isNotEmpty() && it.stationId in stations })
        assertTrue(records.all { it.transitRegion != null })
        val ids = records.map { it.id }.toSet()
        assertTrue(SeedCatalog.parseLegacyIds(data).values.all { it in ids })
    }
}

class CatalogOverlayTest {
    private val seed get() = SeedCatalog.parse(seedJSON)
    private val stations get() = SeedCatalog.parseStations(seedJSON)

    private fun live(
        id: String,
        stationId: String = "",
        stationName: String = "",
        description: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
    ) = AccessibilityCloudClient.Equipment(
        id = id, stationId = stationId, stationName = stationName,
        description = description, isWorking = true,
        latitude = latitude, longitude = longitude,
    )

    // An elevator upstream hasn't linked to a stop place arrives without a
    // station — the seed's (assigned) station makes it searchable.
    @Test
    fun unlinkedLiveRecordTakesTheSeedStation() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(live(id = "3910", description = "→ Bahnsteig 2", latitude = 51.5, longitude = 6.7)),
            seed = seed, stations = stations,
        )
        val record = merged.single()
        assertEquals("name:Dinslaken Bahnhof", record.stationId)
        assertEquals("Dinslaken Bahnhof", record.stationName)
        assertEquals(TransitRegion.NordrheinWestfalen, record.region)
        assertEquals("DB FaSta", record.sourceName)
        assertEquals("DB InfraGO", record.organizationName)
        assertEquals("10855569", record.inventoryId)
        // Live wins where it has a value.
        assertEquals(51.5, record.latitude)
        assertEquals(true, record.isWorking)
    }

    // A linked live record keeps its own station; the seed only fills the
    // rest.
    @Test
    fun linkedLiveRecordKeepsItsStation() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(
                live(id = "6696", stationId = "900130002", stationName = "Pankow (Berlin)",
                    description = "Vorhalle ↔ Bahnsteig U2"),
            ),
            seed = seed, stations = stations,
        )
        val record = merged.single()
        assertEquals("Pankow (Berlin)", record.stationName)
        assertEquals(setOf(TransitNetwork.SBahn, TransitNetwork.UBahn), record.stationNetworks)
        assertEquals(52.567, record.latitude)
        assertEquals("BVG", record.sourceName)
    }

    // With live data, a seed record live doesn't answer for is gone upstream
    // and stays out — no duplicates, no ghosts.
    @Test
    fun seedRecordsMissingLiveAreNotAppended() {
        val merged = EquipmentCatalog.overlaid(live = listOf(live(id = "3910")), seed = seed, stations = stations)
        assertEquals(listOf("3910"), merged.map { it.id })
    }

    @Test
    fun offlineFallbackServesWholeSeedUnknown() {
        val merged = EquipmentCatalog.overlaid(live = emptyList(), seed = seed, stations = stations)
        assertEquals(listOf("6696", "6697", "3910", "4011"), merged.map { it.id })
        assertTrue(merged.all { it.isWorking == null })
    }

    // No centroid: the station's coordinate (and region) from the list.
    @Test
    fun stationListPlacesRecordsWithoutCoordinates() {
        val merged = EquipmentCatalog.overlaid(live = emptyList(), seed = seed, stations = stations)
        assertEquals(52.5671, assertNotNull(merged.first { it.id == "6697" }).latitude)
        val wernau = merged.first { it.id == "4011" }
        assertEquals(48.6890, wernau.latitude)
        assertEquals(TransitRegion.BadenWuerttemberg, wernau.region)
    }

    // An elevator added upstream after the generation: its station's seed
    // records answer for coordinates, region and the unanimous operator.
    @Test
    fun unknownLiveRecordIsBackfilledFromItsStation() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(live(id = "9001", stationId = "900130002", stationName = "Pankow (Berlin)")),
            seed = seed, stations = stations,
        )
        val record = merged.single()
        assertEquals(52.567, record.latitude)
        assertEquals(TransitRegion.Berlin, record.region)
        assertEquals("BVG", record.sourceName)
        assertEquals("Berliner Verkehrsbetriebe (BVG)", record.organizationName)
    }

    @Test
    fun stationFallbackNeverReachesAcrossStations() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(live(id = "9003", stationName = "Ostkreuz (Berlin)")),
            seed = seed, stations = stations,
        )
        val record = merged.single()
        assertNull(record.latitude)
        assertTrue(record.sourceName.isEmpty())
        assertNull(record.region)
    }

    // Station names differ by the network prefix between operator feeds;
    // everything else must keep stations apart.
    @Test
    fun stationKeyFoldsOnlyTheNetworkPrefix() {
        assertEquals(
            EquipmentCatalog.stationKey("U Spittelmarkt (Berlin)"),
            EquipmentCatalog.stationKey("Spittelmarkt (Berlin)"),
        )
        assertEquals(
            EquipmentCatalog.stationKey("S+U Pankow (Berlin)"),
            EquipmentCatalog.stationKey("Pankow (Berlin)"),
        )
        assertEquals(
            EquipmentCatalog.stationKey("S Ostkreuz (Berlin)"),
            EquipmentCatalog.stationKey("ostkreuz (berlin)"),
        )
        assertNotEquals(
            EquipmentCatalog.stationKey("Pankow (Berlin)"),
            EquipmentCatalog.stationKey("Pankow-Heinersdorf (Berlin)"),
        )
        assertNotEquals(EquipmentCatalog.stationKey("Ostkreuz"), EquipmentCatalog.stationKey("Ostkreuz (Berlin)"))
        assertTrue(EquipmentCatalog.stationKey("").isEmpty())
    }
}
