package com.a11yland.hissi.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/SeedCatalogTests.swift.

private val seedJSON = """
{
 "generatedAt": "2026-07-19T21:00:00Z",
 "elevators": [
  {
   "id": "g9DnAE6CioBKy6kbn",
   "source": "accessibilityCloud",
   "acId": "g9DnAE6CioBKy6kbn",
   "stationName": "S Pankow-Heinersdorf (Berlin)",
   "description": "Prenzlauer Promenade ⟷ S-Bahnsteig Gl. 1/2",
   "latitude": 52.577603,
   "longitude": 13.42909,
   "sourceName": "VBB Anlagen (S-Bahn)",
   "organizationName": "VBB",
   "fastaEquipmentNumber": 10906243,
   "region": "berlin"
  },
  {
   "id": "hugGa4c2AnzMaWWQu",
   "source": "accessibilityCloud",
   "acId": "hugGa4c2AnzMaWWQu",
   "stationName": "Waßmannsdorf",
   "description": "Waßmannsdorf zu Gleis 1",
   "latitude": null,
   "longitude": null,
   "sourceName": "VBB Anlagen (S-Bahn)",
   "organizationName": "VBB",
   "fastaEquipmentNumber": 10466002
  },
  {
   "id": "fasta-10466003",
   "source": "fasta",
   "fastaEquipmentNumber": 10466003,
   "stationNumber": 7723,
   "stationName": "Waßmannsdorf",
   "description": "zu Gleis 2",
   "latitude": 52.3683272,
   "longitude": 13.4637534,
   "sourceName": "DB FaSta",
   "organizationName": "DB InfraGO",
   "region": "brandenburg"
  },
  {
   "id": "brokenlifts-900009103-0",
   "source": "brokenlifts",
   "brokenliftsStationId": "900009103",
   "brokenliftsIndex": 0,
   "stationName": "U Seestr.",
   "description": "Aufzug zwischen U-Bahnsteig i. Ri. Alt-Tegel und Müllerstr.",
   "latitude": 52.550471,
   "longitude": 13.351966,
   "sourceName": "brokenlifts.org",
   "organizationName": "Berliner Verkehrsbetriebe (BVG)"
  }
 ]
}
"""

class SeedCatalogTest {
    @Test
    fun parsesAllRecordsWithOptionalFields() {
        val records = SeedCatalog.parse(seedJSON)
        assertEquals(4, records.size)
        val heinersdorf = assertNotNull(records.firstOrNull { it.id == "g9DnAE6CioBKy6kbn" })
        assertEquals(10906243, heinersdorf.fastaEquipmentNumber)
        assertNull(heinersdorf.stationNumber)
        val fastaOnly = assertNotNull(records.firstOrNull { it.source == "fasta" })
        assertEquals(7723, fastaOnly.stationNumber)
        val brokenlifts = assertNotNull(records.firstOrNull { it.source == "brokenlifts" })
        assertEquals("900009103", brokenlifts.brokenliftsStationId)
        assertEquals(0, brokenlifts.brokenliftsIndex)
    }

    @Test
    fun unparseableDataYieldsEmpty() {
        assertTrue(SeedCatalog.parse("nonsense").isEmpty())
    }
}

class CatalogOverlayTest {
    private val seed: List<SeedCatalog.Record> get() = SeedCatalog.parse(seedJSON)

    private fun liveEquipment(
        id: String = "6225",
        inventoryId: String? = "10906243",
        stationName: String = "Pankow-Heinersdorf (Berlin)",
    ) = AccessibilityCloudClient.Equipment(
        id = id,
        inventoryId = inventoryId,
        stationId = "900130011",
        stationName = stationName,
        description = "Straßenland ⟷ Gleis 1/2 (S-Bahn)",
        isWorking = true,
        lastUpdateEpochMillis = Instant.now().toEpochMilli(),
    )

    // The operator inventory number (= the seed's FaSta equipment number)
    // matches live records to the seed, which backfills what the API doesn't
    // serve — names, coordinates, region.
    @Test
    fun inventoryNumberMatchBackfillsSeedMetadata() {
        val merged = EquipmentCatalog.overlaid(live = listOf(liveEquipment()), seed = seed)
        val record = assertNotNull(merged.firstOrNull { it.id == "6225" })
        assertEquals(10906243, record.fastaEquipmentNumber)
        assertEquals("VBB Anlagen (S-Bahn)", record.sourceName)
        assertEquals(52.577603, record.latitude)
        assertEquals(TransitRegion.Berlin, record.region)
        assertEquals(true, record.isWorking)
        // The matched seed record must not reappear as a duplicate.
        assertFalse(merged.any { it.id == "g9DnAE6CioBKy6kbn" })
        assertEquals(3, merged.size) // live + fasta and brokenlifts gap records
    }

    @Test
    fun emptyStationNameIsBackfilledFromSeed() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(liveEquipment(id = "5034", inventoryId = "10466002", stationName = "")),
            seed = seed,
        )
        val record = assertNotNull(merged.firstOrNull { it.id == "5034" })
        assertEquals("Waßmannsdorf", record.stationName)
    }

    // A "fasta" record whose inventory number appears live is answered by the
    // API — it must not be appended on top.
    @Test
    fun fastaRecordMatchedByInventoryIsNotAppended() {
        val live = liveEquipment(id = "4972", inventoryId = "10466003", stationName = "Waßmannsdorf")
        val merged = EquipmentCatalog.overlaid(live = listOf(live), seed = seed)
        assertFalse(merged.any { it.id == "fasta-10466003" })
        assertEquals(10466003, merged.firstOrNull { it.id == "4972" }?.fastaEquipmentNumber)
    }

    // Seed records the live catalog does not answer for stay searchable with
    // unknown status: "fasta" records missing upstream, "brokenlifts" records
    // while their whole station is absent. Unmatched accessibilityCloud
    // records must not duplicate live elevators.
    @Test
    fun unansweredGapRecordsAreAppendedWithUnknownStatus() {
        val merged = EquipmentCatalog.overlaid(live = listOf(liveEquipment()), seed = seed)
        val fastaOnly = assertNotNull(merged.firstOrNull { it.id == "fasta-10466003" })
        assertNull(fastaOnly.isWorking)
        assertEquals("7723", fastaOnly.stationId)
        assertEquals(TransitRegion.Brandenburg, fastaOnly.region)
        // Live catalog has no station 900009103 ⇒ the brokenlifts record fills
        // the gap… (see next test for the covered-station case)
        assertTrue(merged.any { it.id == "brokenlifts-900009103-0" })
        assertFalse(merged.any { it.id == "hugGa4c2AnzMaWWQu" })
    }

    @Test
    fun brokenliftsRecordAtLiveStationIsDropped() {
        val seestrasse = AccessibilityCloudClient.Equipment(
            id = "6743",
            stationId = "900009103",
            stationName = "Seestraße (Berlin)",
            description = "Straße ⟷ U6 (→ Alt-Tegel)",
            isWorking = true,
            lastUpdateEpochMillis = Instant.now().toEpochMilli(),
        )
        val merged = EquipmentCatalog.overlaid(live = listOf(seestrasse), seed = seed)
        // The station is live — a stale seed record would just sit next to
        // the real elevator.
        assertFalse(merged.any { it.id == "brokenlifts-900009103-0" })
    }

    // The inventory number goes stale upstream (~30 of the seed's numbers,
    // e.g. U Spittelmarkt) and thousands of live records have no seed
    // counterpart at all. Since the API serves neither coordinates nor
    // source/operator names, such a record would lose its map and both name
    // rows — the seed records of the same station answer for it.
    @Test
    fun unmatchedRecordIsBackfilledFromItsStation() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(liveEquipment(id = "9001", inventoryId = "5279")),
            seed = seed,
        )
        val record = assertNotNull(merged.firstOrNull { it.id == "9001" })
        assertEquals(52.577603, record.latitude)
        assertEquals(13.42909, record.longitude)
        assertEquals("VBB Anlagen (S-Bahn)", record.sourceName)
        assertEquals("VBB", record.organizationName)
        assertEquals(TransitRegion.Berlin, record.region)
        // The station answers for metadata, never for identity: the number
        // is what favorites bridge on.
        assertNull(record.fastaEquipmentNumber)
        // …and the seed record it borrowed from stays a match for its own
        // live record, not a duplicate.
        assertFalse(merged.any { it.id == "g9DnAE6CioBKy6kbn" })
    }

    // Waßmannsdorf carries a VBB and a DB FaSta record — who runs this lift
    // is genuinely unknown, so those rows stay empty. The coordinates are a
    // station property and fill regardless.
    @Test
    fun ambiguousStationLeavesSourceAndOperatorEmpty() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(
                liveEquipment(id = "9002", inventoryId = "5279", stationName = "Waßmannsdorf")
            ),
            seed = seed,
        )
        val record = assertNotNull(merged.firstOrNull { it.id == "9002" })
        assertEquals(52.3683272, record.latitude)
        assertTrue(record.sourceName.isEmpty())
        assertTrue(record.organizationName.isEmpty())
        assertEquals(TransitRegion.Brandenburg, record.region)
    }

    // A matched seed record can itself carry no coordinates — its siblings
    // at the same station do.
    @Test
    fun matchedRecordWithoutSeedCoordinatesFallsBackToItsStation() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(
                liveEquipment(id = "5034", inventoryId = "10466002", stationName = "Waßmannsdorf")
            ),
            seed = seed,
        )
        val record = assertNotNull(merged.firstOrNull { it.id == "5034" })
        assertEquals(52.3683272, record.latitude)
        // The match itself named the source — no ambiguity to resolve.
        assertEquals("VBB Anlagen (S-Bahn)", record.sourceName)
    }

    @Test
    fun stationFallbackNeverReachesAcrossStations() {
        val merged = EquipmentCatalog.overlaid(
            live = listOf(
                liveEquipment(id = "9003", inventoryId = null, stationName = "Ostkreuz (Berlin)")
            ),
            seed = seed,
        )
        val record = assertNotNull(merged.firstOrNull { it.id == "9003" })
        assertNull(record.latitude)
        assertTrue(record.sourceName.isEmpty())
        assertNull(record.region)
    }

    // The two datasets differ by the network prefix; everything else must
    // keep stations apart.
    @Test
    fun stationKeyFoldsOnlyTheNetworkPrefix() {
        assertEquals(
            EquipmentCatalog.stationKey("Spittelmarkt (Berlin)"),
            EquipmentCatalog.stationKey("U Spittelmarkt (Berlin)"),
        )
        assertEquals(
            EquipmentCatalog.stationKey("Pankow (Berlin)"),
            EquipmentCatalog.stationKey("S+U Pankow (Berlin)"),
        )
        assertEquals(
            EquipmentCatalog.stationKey("ostkreuz (berlin)"),
            EquipmentCatalog.stationKey("S Ostkreuz (Berlin)"),
        )
        assertNotEquals(
            EquipmentCatalog.stationKey("Pankow (Berlin)"),
            EquipmentCatalog.stationKey("Pankow-Heinersdorf (Berlin)"),
        )
        assertNotEquals(
            EquipmentCatalog.stationKey("Ostkreuz"),
            EquipmentCatalog.stationKey("Ostkreuz (Berlin)"),
        )
        assertTrue(EquipmentCatalog.stationKey("").isEmpty())
    }

    @Test
    fun offlineFallbackServesWholeSeed() {
        val merged = EquipmentCatalog.overlaid(live = emptyList(), seed = seed)
        assertEquals(4, merged.size)
        assertTrue(merged.all { it.isWorking == null })
        // Seeds from before the region field existed decode to null.
        assertNull(merged.firstOrNull { it.id == "brokenlifts-900009103-0" }?.region)
    }
}
