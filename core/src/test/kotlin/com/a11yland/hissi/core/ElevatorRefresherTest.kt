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
    private val nationalRequests = mutableListOf<Set<String>>()

    private fun favorite(id: String, stationId: String = "1") = MonitoredElevator(
        id = id, stationId = stationId, elevatorId = id,
        stationName = "S Teststadt", elevatorDescription = "Aufzug",
        isWorking = true,
    )

    private fun sources(
        equipment: Map<String, AccessibilityCloudClient.Equipment> = emptyMap(),
        national: Map<String, AccessibilityCloudClient.Equipment> = emptyMap(),
    ) = StatusSources(
        equipment = { ids ->
            equipmentRequests.add(ids)
            equipment
        },
        national = { ids ->
            nationalRequests.add(ids)
            national
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
        // Nothing beyond the VBB to ask for: the national API is not called.
        assertTrue(nationalRequests.isEmpty())
    }

    // Favorites split by id namespace: numeric ids go to the catalog batch,
    // "source:…" ids to the Aufzugsstatus API — each source asked once, and
    // the answers land on the right favorites in the original order.
    @Test
    fun routesNationalIdsToTheNationalSource() = runTest {
        val (updated, failed) = ElevatorRefresher.refreshAll(
            listOf(favorite(id = "101"), favorite(id = "fasta:10804843"), favorite(id = "hvv:Master:11950-E")),
            sources = sources(
                equipment = mapOf("101" to equipment(id = "101", isWorking = true)),
                national = mapOf(
                    "fasta:10804843" to equipment(id = "fasta:10804843", isWorking = false),
                    "hvv:Master:11950-E" to equipment(id = "hvv:Master:11950-E", isWorking = null),
                ),
            ),
        )
        assertContentEquals(listOf("101", "fasta:10804843", "hvv:Master:11950-E"), updated.map { it.id })
        assertContentEquals(listOf(true, false, null), updated.map { it.isWorking })
        assertTrue(failed.isEmpty())
        assertEquals(listOf(setOf("101")), equipmentRequests)
        assertEquals(listOf(setOf("fasta:10804843", "hvv:Master:11950-E")), nationalRequests)
    }

    @Test
    fun nationalOnlyFavoritesSkipTheCatalog() = runTest {
        val (updated, failed) = ElevatorRefresher.refreshAll(
            listOf(favorite(id = "kvb:034-01")),
            sources = sources(),
        )
        // Unanswered: previous values survive, the id is reported.
        assertContentEquals(listOf(true), updated.map { it.isWorking })
        assertEquals(setOf("kvb:034-01"), failed)
        assertTrue(equipmentRequests.isEmpty())
        assertEquals(listOf(setOf("kvb:034-01")), nationalRequests)
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
