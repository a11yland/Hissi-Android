package com.a11yland.hissi.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The screenshot catalog over the real bundled seed — ported from
// HissiTests/Tests/DemoFixturesTests.swift. Gradle runs the tests in the
// module directory.
class DemoFixturesTest {
    private val seed = SeedCatalog.parse(File("../app/src/main/assets/seed-catalog.json").readText())
    private val now = 1_800_000_000_000L
    private val catalog get() = DemoFixtures.catalog(seed, now, "de")

    @Test
    fun everySeedRecordKeepsItsNumericId() {
        assertTrue(catalog.all { ElevatorSource.of(it.id).isLive })
        assertEquals(catalog.size, catalog.map { it.id }.toSet().size)
    }

    @Test
    fun everyCuratedPickExists() {
        for (pick in DemoFixtures.broken + DemoFixtures.unknown + DemoFixtures.favoritePicks) {
            assertTrue(seed.any(pick::matches), "${pick.station} / ${pick.description}")
        }
    }

    @Test
    fun statusesAreCurated() {
        assertEquals(DemoFixtures.broken.size, catalog.count { it.isWorking == false })
        assertEquals(DemoFixtures.unknown.size, catalog.count { it.isWorking == null })
        assertTrue(catalog.filter { it.isWorking == false }.all { it.stateExplanation != null })
        // Fresh enough never to trip the stale-data warning.
        assertTrue(catalog.mapNotNull { it.lastUpdateEpochMillis }.all { now - it < 2 * 86_400_000L })
    }

    // The overlay must answer every seed record with its demo counterpart
    // (same id), not append status-unknown duplicates next to it.
    @Test
    fun overlayAddsNoSeedDuplicates() {
        val overlaid = EquipmentCatalog.overlaid(live = catalog, seed = seed)
        assertEquals(catalog.map { it.id }, overlaid.map { it.id })
    }

    @Test
    fun favoritesStartWithOneBrokenElevator() {
        val favorites = DemoFixtures.favorites(catalog, now)
        assertEquals(DemoFixtures.favoritePicks.size, favorites.size)
        assertEquals(1, favorites.count { it.isWorking == false })
        val added = favorites.map { it.addedAtEpochMillis!! }
        assertEquals(added.sortedDescending(), added)
    }

    // Alexanderplatz — the location the screenshot script sets.
    @Test
    fun nearbyShowsABrokenStation() {
        val stations = NearbyStations.nearest(52.5219, 13.4132, catalog)
        assertTrue(stations.size >= 3)
        assertTrue(stations.any { it.status == ElevatorStatus.Broken })
    }
}
