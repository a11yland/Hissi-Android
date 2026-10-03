package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals

// The pure counterpart of the iOS FavoritesStore.migrateIds test (the
// UserDefaults-bound cleanup suites are iOS-only).
class FavoritesOpsTest {
    private fun favorite(id: String) = MonitoredElevator(
        id = id, stationId = "1", elevatorId = id,
        stationName = "S Teststadt", elevatorDescription = "Aufzug",
        isWorking = null,
    )

    // The id migration after a bridged refresh rewrites only the listed
    // favorites — a favorite added while the refresh ran survives, and the
    // same elevator favorited under both its seed and live id collapses.
    @Test
    fun migrateIdsRewritesOnlyTheListedFavorites() {
        val favorites = listOf(favorite("fasta-42"), favorite("6225"), favorite("456"))
        val live = MonitoredElevator(
            id = "456", stationId = "900000001", elevatorId = "456",
            stationName = "S Teststadt", elevatorDescription = "Aufzug",
            isWorking = true,
        )
        val migrated = FavoritesOps.migrated(favorites, mapOf("fasta-42" to live))
        assertContentEquals(listOf("456", "6225"), migrated.map { it.id })
        assertContentEquals(
            listOf("456", "6225"),
            FavoritesOps.migrated(migrated, emptyMap()).map { it.id },
        )
    }

    @Test
    fun removeFilters() {
        val favorites = listOf(favorite("101"))
        assertContentEquals(emptyList(), FavoritesOps.removed(favorites, "101").map { it.id })
    }
}
