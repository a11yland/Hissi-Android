package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ported 1:1 from HissiTests/Tests/TransitClassificationTests.swift.

class TransitRegionTest {
    @Test
    fun agsPrefixDecidesRegion() {
        assertEquals(TransitRegion.Berlin, TransitRegion.fromOriginalPlaceInfoId("de:11000:900100015"))
        assertEquals(TransitRegion.Brandenburg, TransitRegion.fromOriginalPlaceInfoId("de:12054:900230999"))
    }

    @Test
    fun nonAgsIdsYieldNil() {
        // BVG records put the station name into originalPlaceInfoId.
        assertNull(TransitRegion.fromOriginalPlaceInfoId("S+U Rathaus Spandau (Berlin)"))
        assertNull(TransitRegion.fromOriginalPlaceInfoId(""))
        assertNull(TransitRegion.fromOriginalPlaceInfoId(null))
    }

    @Test
    fun nameHeuristicCoversAllConventions() {
        assertEquals(TransitRegion.Berlin, TransitRegion.inferred("S Adlershof (Berlin)"))
        assertEquals(TransitRegion.Berlin, TransitRegion.inferred("U Klosterstraße"))      // BVG, no suffix
        assertEquals(TransitRegion.Berlin, TransitRegion.inferred("S+U Pankow (Berlin)"))
        assertEquals(TransitRegion.Berlin, TransitRegion.inferred("Berlin Ostbahnhof"))    // DB prefix
        assertEquals(TransitRegion.Brandenburg, TransitRegion.inferred("Waßmannsdorf"))
        assertEquals(TransitRegion.Brandenburg, TransitRegion.inferred("Potsdam Hauptbahnhof"))
    }
}

class TransitNetworkTest {
    private val bvg = "BVG Elevators (2025)"

    @Test
    fun sourceFeedsSettleUnambiguousRecords() {
        assertEquals(
            TransitNetwork.SBahn,
            TransitNetwork.classify(
                description = "Prenzlauer Promenade ⟷ S-Bahnsteig Gl. 1/2",
                stationName = "S Pankow-Heinersdorf (Berlin)",
                sourceName = "VBB Anlagen (S-Bahn)",
            ),
        )
        assertEquals(
            TransitNetwork.Regional,
            TransitNetwork.classify(
                description = "Zugang Bahnsteig Gl. 11/12",
                stationName = "S+U Lichtenberg Bhf (Berlin)",
                sourceName = "VBB-Anlagen (DB Regio)",
            ),
        )
    }

    @Test
    fun fastaFallsBackToStationPrefix() {
        assertEquals(
            TransitNetwork.SBahn,
            TransitNetwork.classify(
                description = "zu Gleis 1", stationName = "S Mahlow", sourceName = "DB FaSta",
            ),
        )
        // Without station modes (seed-only records), unprefixed S-Bahn
        // stations still classify as regional.
        assertEquals(
            TransitNetwork.Regional,
            TransitNetwork.classify(
                description = "zu Gleis 2", stationName = "Waßmannsdorf", sourceName = "DB FaSta",
            ),
        )
    }

    // The stop place's transport modes settle what neither feed nor name
    // carries: DB records at unprefixed S-Bahn stations, and unmarked
    // elevators at single-network stations.
    @Test
    fun stationModesSettleUnprefixedAndUnmarkedRecords() {
        assertEquals(
            TransitNetwork.SBahn,
            TransitNetwork.classify(
                description = "zu Gleis 2", stationName = "Waßmannsdorf",
                sourceName = "DB FaSta", stationModes = setOf(TransitNetwork.SBahn),
            ),
        )
        // Mixed DB station without an "S " prefix stays regional.
        assertEquals(
            TransitNetwork.Regional,
            TransitNetwork.classify(
                description = "zu Gleis 2", stationName = "Königs Wusterhausen",
                sourceName = "DB FaSta",
                stationModes = setOf(TransitNetwork.SBahn, TransitNetwork.Regional),
            ),
        )
        // Unmarked elevator, no source: the station's single network wins.
        assertEquals(
            TransitNetwork.Regional,
            TransitNetwork.classify(
                description = "zu Gleis 1/1a", stationName = "Wittenberge, Bahnhof",
                sourceName = "", stationModes = setOf(TransitNetwork.Regional),
            ),
        )
        // Multi-network station keeps the "Zugang" bucket for unmarked lifts.
        assertEquals(
            TransitNetwork.Access,
            TransitNetwork.classify(
                description = "Straße ⟷ Zwischenebene",
                stationName = "S+U Rathaus Spandau (Berlin)",
                sourceName = "BVG Elevators (2025)",
                stationModes = setOf(TransitNetwork.SBahn, TransitNetwork.UBahn),
            ),
        )
    }

    @Test
    fun transportModeIdsMapToNetworks() {
        assertEquals(TransitNetwork.SBahn, TransitNetwork.fromTransportModeId(1))
        assertEquals(TransitNetwork.UBahn, TransitNetwork.fromTransportModeId(2))
        assertEquals(TransitNetwork.Regional, TransitNetwork.fromTransportModeId(3))
        assertNull(TransitNetwork.fromTransportModeId(9))
    }

    // The BVG feed carries U- and S-platform elevators at S+U stations; the
    // description has to tell them apart (e.g. Pankow).
    @Test
    fun descriptionSplitsBvgRecordsAtMixedStations() {
        assertEquals(
            TransitNetwork.UBahn,
            TransitNetwork.classify(
                description = "Vorhalle ⟷ Bahnsteig U2",
                stationName = "S+U Pankow (Berlin)", sourceName = bvg,
            ),
        )
        assertEquals(
            TransitNetwork.SBahn,
            TransitNetwork.classify(
                description = "Vorhalle ⟷ Bahnsteig S-Bahn",
                stationName = "S+U Pankow (Berlin)", sourceName = bvg,
            ),
        )
        assertEquals(
            TransitNetwork.UBahn,
            TransitNetwork.classify(
                description = "Zwischenebene Klosterstraße (Süd) ⟷ Bahnsteig U7 Richtung Rudow",
                stationName = "S+U Rathaus Spandau (Berlin)", sourceName = bvg,
            ),
        )
    }

    @Test
    fun unmarkedElevatorsAtMixedStationsAreAccess() {
        assertEquals(
            TransitNetwork.Access,
            TransitNetwork.classify(
                description = "Straße ⟷ Zwischenebene Klosterstraße (Süd)",
                stationName = "S+U Rathaus Spandau (Berlin)", sourceName = bvg,
            ),
        )
        // "Übergang S1" must not count as an S-Bahn platform marker.
        assertEquals(
            TransitNetwork.Access,
            TransitNetwork.classify(
                description = "Straße Kuhligkshofstraße Übergang S1 ⟷ Zwischenebene",
                stationName = "S+U Rathaus Steglitz (Berlin)", sourceName = bvg,
            ),
        )
    }

    @Test
    fun stationPrefixAndSourceCoverUnmarkedRecords() {
        assertEquals(
            TransitNetwork.UBahn,
            TransitNetwork.classify(
                description = "Aufzug zwischen U-Bahnsteig und Zugang Klosterstraße",
                stationName = "U Klosterstraße", sourceName = "brokenlifts.org",
            ),
        )
        assertEquals(
            TransitNetwork.UBahn,
            TransitNetwork.classify(
                description = "Straße ⟷ Bahnsteig",
                stationName = "U Vinetastr. (Berlin)", sourceName = bvg,
            ),
        )
        // Renamed BVG station without a "U " prefix.
        assertEquals(
            TransitNetwork.UBahn,
            TransitNetwork.classify(
                description = "Zwischenebene ⟷ Bahnsteig",
                stationName = "Anton-Wilhelm-Amo-Straße  (Berlin)", sourceName = bvg,
            ),
        )
    }
}

class SearchGroupingTest {
    private fun equipment(
        id: String, station: String, description: String, source: String,
        region: TransitRegion? = null,
    ) = AccessibilityCloudClient.Equipment(
        id = id, stationId = "1", stationName = station, description = description,
        isWorking = true, sourceName = source, region = region,
    )

    private val pankowMixed = listOf(
        equipment(
            id = "u1", station = "S+U Pankow (Berlin)",
            description = "Vorhalle ⟷ Bahnsteig U2",
            source = "BVG Elevators (2025)", region = TransitRegion.Berlin,
        ),
        equipment(
            id = "s1", station = "S+U Pankow (Berlin)",
            description = "Vorhalle ⟷ Bahnsteig S-Bahn",
            source = "BVG Elevators (2025)", region = TransitRegion.Berlin,
        ),
        equipment(
            id = "b1", station = "Bernau, Bahnhof",
            description = "Zugang Gleis 1",
            source = "VBB-Anlagen (DB Regio)", region = TransitRegion.Brandenburg,
        ),
    )

    @Test
    fun splitsRegionsBerlinFirst() {
        val regions = SearchGrouping.group(pankowMixed)
        assertContentEquals(listOf(TransitRegion.Berlin, TransitRegion.Brandenburg), regions.map { it.region })
        assertContentEquals(listOf("S+U Pankow (Berlin)"), regions[0].stations.map { it.station.name })
        assertContentEquals(listOf("Bernau, Bahnhof"), regions[1].stations.map { it.station.name })
    }

    @Test
    fun mixedStationSplitsIntoOrderedNetworkSections() {
        val regions = SearchGrouping.group(pankowMixed)
        val pankow = assertNotNull(regions.firstOrNull()?.stations?.firstOrNull())
        assertContentEquals(listOf(TransitNetwork.UBahn, TransitNetwork.SBahn), pankow.networks)
        assertContentEquals(listOf("u1"), pankow.sections[0].elevators.map { it.id })
        assertContentEquals(listOf("s1"), pankow.sections[1].elevators.map { it.id })
    }

    @Test
    fun missingRegionFallsBackToNameHeuristic() {
        val regions = SearchGrouping.group(
            listOf(
                equipment(
                    id = "k1", station = "U Klosterstraße",
                    description = "Aufzug zwischen U-Bahnsteig und Zugang Klosterstraße",
                    source = "brokenlifts.org",
                ),
                equipment(
                    id = "w1", station = "Waßmannsdorf",
                    description = "zu Gleis 2", source = "DB FaSta",
                ),
            ),
        )
        assertContentEquals(listOf(TransitRegion.Berlin, TransitRegion.Brandenburg), regions.map { it.region })
    }

    @Test
    fun singleRegionYieldsSingleGroup() {
        val regions = SearchGrouping.group(listOf(pankowMixed[0], pankowMixed[1]))
        assertEquals(1, regions.size)
        assertEquals(TransitRegion.Berlin, regions[0].region)
    }

    // The detail view's targeted refresh flows back into the row: same
    // grouping, same order, one elevator swapped — id adoption included.
    @Test
    fun replacingSwapsOneElevatorAndKeepsTheShape() {
        val regions = SearchGrouping.group(pankowMixed)
        val before = regions.first().stations.first()
        val old = before.sections.flatMap { it.elevators }.first { it.id == "s1" }
        val fresh = MonitoredElevator(
            id = "4711", stationId = old.stationId, elevatorId = "4711",
            stationName = old.stationName, elevatorDescription = old.elevatorDescription,
            isWorking = false, stateExplanation = "Defekt",
        )

        val after = SearchGrouping.replacing(regions, id = "s1", elevator = fresh)
        assertEquals(regions.map { it.region }, after.map { it.region })
        assertEquals(regions.map { r -> r.stations.map { it.id } }, after.map { r -> r.stations.map { it.id } })
        val station = after.first().stations.first()
        assertEquals(before.sections.map { it.network }, station.sections.map { it.network })
        val ids = station.sections.flatMap { it.elevators }.map { it.id }
        assertTrue("4711" in ids && "s1" !in ids && "u1" in ids)
        val swapped = station.sections.flatMap { it.elevators }.first { it.id == "4711" }
        assertEquals(false, swapped.isWorking)
        assertEquals("Defekt", swapped.stateExplanation)
        // Unknown id: nothing changes.
        assertEquals(regions, SearchGrouping.replacing(regions, id = "nope", elevator = fresh))
    }
}
