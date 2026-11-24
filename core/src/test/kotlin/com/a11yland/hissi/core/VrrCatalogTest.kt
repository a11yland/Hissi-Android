package com.a11yland.hissi.core

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

// Ported from HissiTests/Tests/VRRCatalogTests.swift. Two sources, one
// catalog: each answers for its own elevators only.
private const val VRR_ID = "vrr:de:05112:16580:31"
private const val VRR_SIBLING_ID = "vrr:de:05112:16580:32"
private const val DHID = "de:05112:16580"

private val seed = listOf(
    SeedCatalog.Record(id = "101", stationId = "900000001", stationName = "S Teststadt", description = "Aufzug"),
    SeedCatalog.Record(id = "102", stationId = "900000001", stationName = "S Teststadt", description = "Aufzug 2"),
    SeedCatalog.Record(
        id = VRR_ID, stationId = DHID, stationName = "Duisburg Duissern U-Bf", region = "nordrhein-westfalen",
        description = "Straßenebene zum Bahnsteig", sourceName = "VRR",
    ),
)

private val stations = listOf(
    SeedCatalog.Station(
        stationId = DHID, dhid = DHID, name = "Duisburg Duissern U-Bf", region = "nordrhein-westfalen",
        latitude = 51.439212, longitude = 6.782882,
    ),
)

private fun live(id: String, isWorking: Boolean? = true, station: String = ""): AccessibilityCloudClient.Equipment {
    val isVrr = ElevatorSource.of(id) == ElevatorSource.Vrr
    return AccessibilityCloudClient.Equipment(
        id = id, stationId = if (isVrr) DHID else "", stationName = station, description = "",
        isWorking = isWorking, lastUpdateEpochMillis = Instant.now().toEpochMilli(),
        sourceName = if (isVrr) "VRR" else "",
    )
}

class PerSourceOverlayTest {
    private fun overlaid(vararg records: AccessibilityCloudClient.Equipment) =
        EquipmentCatalog.overlaid(live = records.toList(), seed = seed, stations = stations)

    // accessibility.cloud answered, VRR didn't: the VRR elevators stand in
    // from the seed, status unknown — and an accessibility.cloud elevator
    // missing from its own answer is still gone.
    @Test
    fun vrrOutageKeepsTheVrrSeedRecords() {
        val merged = overlaid(live("101", isWorking = false))
        assertEquals(listOf("101", VRR_ID), merged.map { it.id })
        assertEquals(false, merged.first().isWorking)
        assertNull(merged.last().isWorking)
        assertEquals("Duisburg Duissern U-Bf", merged.last().stationName)
        assertEquals(51.439212, merged.last().latitude)
    }

    @Test
    fun accessibilityCloudOutageKeepsItsSeedRecords() {
        val merged = overlaid(live(VRR_ID, isWorking = false))
        assertEquals(listOf(VRR_ID, "101", "102"), merged.map { it.id })
        assertEquals(false, merged.first().isWorking)
        assertTrue(merged.drop(1).all { it.isWorking == null })
    }

    @Test
    fun bothAnswerAndEachDropsWhatItNoLongerHas() {
        // 101 is gone at accessibility.cloud, the seeded VRR lift at VRR.
        assertEquals(listOf("102", VRR_SIBLING_ID), overlaid(live("102"), live(VRR_SIBLING_ID)).map { it.id })
    }

    @Test
    fun noLiveDataAtAllServesTheWholeSeedUnknown() {
        val merged = overlaid()
        assertEquals(listOf("101", "102", VRR_ID), merged.map { it.id })
        assertTrue(merged.all { it.isWorking == null })
    }

    // The feed's raw stop name would split the station in search and nearby
    // (they group by name) — the seed's normalised one wins.
    @Test
    fun vrrStationNameComesFromTheSeed() {
        val merged = overlaid(live(VRR_ID, station = "DU-Duissern U")).first()
        assertEquals("Duisburg Duissern U-Bf", merged.stationName)
        assertEquals(DHID, merged.stationId)
        assertEquals("Straßenebene zum Bahnsteig", merged.description)
    }

    // A lift added since the generation, at a stop the seed knows.
    @Test
    fun newVrrLiftTakesItsStopsSeedName() {
        val merged = overlaid(live(VRR_SIBLING_ID, station = "DU-Duissern U")).first()
        assertEquals("Duisburg Duissern U-Bf", merged.stationName)
        assertEquals(51.439212, merged.latitude)
        assertEquals(TransitRegion.NordrheinWestfalen, merged.region)
    }

    // accessibility.cloud records keep the old rule: live names win.
    @Test
    fun accessibilityCloudStationNameStaysLive() {
        assertEquals("S Teststadt (neu)", overlaid(live("101", station = "S Teststadt (neu)")).first().stationName)
    }
}

class VrrCatalogTest {
    private var builds = 0
    private val targeted = mutableListOf<List<String>>()
    private var vrrFetches = 0

    private fun tempCacheFile(): File = File.createTempFile("vrr-catalog", ".json").apply { delete() }

    private fun TestScope.catalog(
        ttl: Duration = Duration.ofMinutes(10),
        cacheFile: File = tempCacheFile(),
        accessibilityCloud: List<AccessibilityCloudClient.Equipment>? = listOf(live("101"), live("102")),
        vrr: List<AccessibilityCloudClient.Equipment>? = listOf(live(VRR_ID, isWorking = false)),
        vrrEnabled: Boolean = true,
    ) = CatalogCache(
        ttl = ttl,
        cacheFile = cacheFile,
        seed = seed.filter { ElevatorSource.includes(it.id, vrrEnabled) },
        stations = stations,
        scope = this,
        fetchTargeted = { ids ->
            targeted.add(ids)
            accessibilityCloud?.filter { it.id in ids }
        },
        buildLive = {
            builds += 1
            accessibilityCloud
        },
        fetchVrr = {
            vrrFetches += 1
            if (vrrEnabled) vrr else null
        },
        includes = { ElevatorSource.includes(it, vrrEnabled) },
    )

    @Test
    fun buildCombinesBothSources() = runTest {
        val built = assertNotNull(catalog().all())
        assertEquals(listOf("101", "102", VRR_ID), built.map { it.id })
        assertEquals(false, built.last().isWorking)
        assertEquals(1, builds)
        assertEquals(1, vrrFetches)
    }

    @Test
    fun buildSurvivesAVrrOutage() = runTest {
        val built = assertNotNull(catalog(vrr = null).all())
        assertEquals(listOf("101", "102", VRR_ID), built.map { it.id })
        assertNull(built.last().isWorking)
    }

    // Without accessibility.cloud there is no catalog build — as before; the
    // seed answers, nothing is cached as fresh.
    @Test
    fun buildFailsWithoutAccessibilityCloud() = runTest {
        val catalog = catalog(accessibilityCloud = null)
        assertTrue(assertNotNull(catalog.all()).all { it.isWorking == null })
        assertNull(catalog.snapshot(rebuildingIfStale = false))
    }

    // The targeted path asks each source only for what is its own.
    @Test
    fun targetedRefreshRoutesBySource() = runTest {
        val catalog = catalog(ttl = Duration.ZERO)
        assertEquals(setOf("101"), catalog.equipment(setOf("101")).keys)
        assertEquals(listOf(listOf("101")), targeted)
        assertEquals(0, vrrFetches)

        val mixed = catalog.equipment(setOf("102", VRR_ID))
        assertEquals(true, mixed["102"]?.isWorking)
        assertEquals(false, mixed[VRR_ID]?.isWorking)
        assertEquals("Duisburg Duissern U-Bf", mixed[VRR_ID]?.stationName)
        assertEquals(listOf(listOf("101"), listOf("102")), targeted)
        assertEquals(1, vrrFetches)

        catalog.equipment(setOf(VRR_ID))
        // No accessibility.cloud request for a VRR id.
        assertEquals(2, targeted.size)
        assertEquals(2, vrrFetches)
    }

    @Test
    fun targetedVrrOutageAnswersUnknownWhileTheOtherSourceIsLive() = runTest {
        val answer = catalog(ttl = Duration.ZERO, vrr = null).equipment(setOf("101", VRR_ID))
        assertEquals(true, answer["101"]?.isWorking)
        assertNotNull(answer[VRR_ID])
        assertNull(answer[VRR_ID]?.isWorking)
    }

    @Test
    fun targetedAccessibilityCloudOutageLeavesVrrLive() = runTest {
        val answer = catalog(ttl = Duration.ZERO, accessibilityCloud = null).equipment(setOf("101", VRR_ID))
        assertNull(answer["101"]?.isWorking)
        assertEquals(false, answer[VRR_ID]?.isWorking)
    }

    // A fresh catalog answers both sources without a request …
    @Test
    fun freshCatalogAnswersBothSources() = runTest {
        val catalog = catalog()
        catalog.all()
        assertEquals(false, catalog.equipment(setOf("101", VRR_ID))[VRR_ID]?.isWorking)
        assertTrue(targeted.isEmpty())
        assertEquals(1, vrrFetches)
    }

    // … unless it was built while VRR didn't answer: its VRR records are
    // seed stand-ins, so a VRR favorite asks again instead of reading
    // "unbekannt" until the cache expires. Other favorites still cost nothing.
    @Test
    fun freshCatalogBuiltDuringAVrrOutageAsksVrrAgain() = runTest {
        val file = tempCacheFile()
        catalog(cacheFile = file, vrr = null).all()
        val reader = catalog(cacheFile = file)
        assertEquals(true, reader.equipment(setOf("101"))["101"]?.isWorking)
        assertEquals(1, vrrFetches)
        val answer = reader.equipment(setOf("101", VRR_ID))
        assertEquals(false, answer[VRR_ID]?.isWorking)
        assertEquals(true, answer["101"]?.isWorking)
        assertEquals(2, vrrFetches)
        assertTrue(targeted.isEmpty())
    }

    // Switching the source off hides what an earlier run persisted.
    @Test
    fun toggleOffFiltersPersistedVrrRecords() = runTest {
        val file = tempCacheFile()
        catalog(cacheFile = file).all()
        val reader = catalog(cacheFile = file, vrrEnabled = false)
        assertEquals(listOf("101", "102"), reader.snapshot(rebuildingIfStale = false)?.map { it.id })
        assertTrue(reader.equipment(setOf(VRR_ID)).isEmpty())
    }
}
