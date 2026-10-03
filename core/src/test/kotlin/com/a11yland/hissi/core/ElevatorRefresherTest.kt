package com.a11yland.hissi.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// Ported 1:1 from HissiTests/Tests/ElevatorRefresherTests.swift. (The
// LegacyFavoritesCleanupTests are iOS-only — this app never stored
// pre-migration ids; the pure migrateIds counterpart is in FavoritesOpsTest.)
class ElevatorRefresherTest {
    private val equipmentRequests = mutableListOf<Set<String>>()

    private fun favorite(id: String, stationId: String = "1") = MonitoredElevator(
        id = id, stationId = stationId, elevatorId = id,
        stationName = "S Teststadt", elevatorDescription = "Aufzug",
        isWorking = true,
    )

    private fun sources(
        equipment: Map<String, AccessibilityCloudClient.Equipment> = emptyMap(),
    ) = StatusSources(
        equipment = { ids ->
            equipmentRequests.add(ids)
            equipment
        },
    )

    private fun equipment(id: String, isWorking: Boolean?) = AccessibilityCloudClient.Equipment(
        id = id, stationId = "900000001", stationName = "S Teststadt",
        description = "Straße ⟷ Bahnsteig", isWorking = isWorking,
        lastUpdateEpochMillis = Instant.now().toEpochMilli(),
    )

    @Test
    fun allFavoritesResolveFromOneCatalogBatch() = runTest {
        val (updated, failed) = ElevatorRefresher.refreshAll(
            listOf(favorite(id = "101"), favorite(id = "102")),
            sources = sources(
                equipment = mapOf(
                    "101" to equipment(id = "101", isWorking = false),
                    "102" to equipment(id = "102", isWorking = true),
                ),
            ),
        )
        assertContentEquals(listOf(false, true), updated.map { it.isWorking })
        assertTrue(failed.isEmpty())
        // One batch covering both ids — never one request per favorite.
        assertEquals(listOf(setOf("101", "102")), equipmentRequests)
    }

    @Test
    fun totalFailureKeepsValuesAndReportsIds() = runTest {
        val favorites = listOf(favorite(id = "101"), favorite(id = "102"))
        val (updated, failed) = ElevatorRefresher.refreshAll(favorites, sources = sources())
        // Nothing resolved: previous values survive, both ids are reported.
        assertContentEquals(listOf(true, true), updated.map { it.isWorking })
        assertEquals(setOf("101", "102"), failed)
    }

    @Test
    fun emptyFavoritesDoNotTouchTheCatalog() = runTest {
        val (updated, failed) = ElevatorRefresher.refreshAll(emptyList(), sources = sources())
        assertTrue(updated.isEmpty() && failed.isEmpty())
        assertTrue(equipmentRequests.isEmpty())
    }

    // A seed-id favorite answered with its bridged live record adopts the
    // live identity (the app then persists the id migration).
    @Test
    fun bridgedFavoriteAdoptsTheLiveIdentity() = runTest {
        val (updated, failed) = ElevatorRefresher.refreshAll(
            listOf(favorite(id = "fasta-42")),
            sources = sources(
                equipment = mapOf("fasta-42" to equipment(id = "456", isWorking = false)),
            ),
        )
        assertContentEquals(listOf("456"), updated.map { it.id })
        assertContentEquals(listOf(false), updated.map { it.isWorking })
        assertTrue(failed.isEmpty())
    }
}
