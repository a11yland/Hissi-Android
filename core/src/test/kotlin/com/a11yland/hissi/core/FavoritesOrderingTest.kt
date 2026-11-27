package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// Ported from `HissiTests/Tests/FavoritesOrderingTests.swift`. The Swift
// suite's backfill tests are iOS-only upgrade machinery (Android favorites
// never existed without addedAt before a release), and `moved` asserts the
// plain target-index contract instead of SwiftUI's insertion offset.
class FavoritesOrderingTest {
    private val reference = 1_700_000_000_000L

    private fun favorite(id: String, addedAtEpochMillis: Long? = null) = MonitoredElevator(
        id = id, stationId = "1", elevatorId = id,
        stationName = "S Teststadt", elevatorDescription = "Aufzug",
        isWorking = true, addedAtEpochMillis = addedAtEpochMillis,
    )

    // MARK: Default order

    @Test
    fun recentlyAddedSortsNewestFirst() {
        val sorted = FavoritesOrdering.byRecentlyAdded(
            listOf(
                favorite("a", addedAtEpochMillis = reference),
                favorite("b", addedAtEpochMillis = reference + 60_000),
                favorite("c", addedAtEpochMillis = reference - 60_000),
            ),
        )
        assertContentEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    // Favorites stored before addedAt existed must not jump to the top just
    // because they carry no timestamp.
    @Test
    fun favoritesWithoutTimestampSortLastKeepingTheirOrder() {
        val sorted = FavoritesOrdering.byRecentlyAdded(
            listOf(
                favorite("legacy1"),
                favorite("new", addedAtEpochMillis = reference),
                favorite("legacy2"),
            ),
        )
        assertContentEquals(listOf("new", "legacy1", "legacy2"), sorted.map { it.id })
    }

    @Test
    fun equalTimestampsKeepTheirRelativeOrder() {
        val sorted = FavoritesOrdering.byRecentlyAdded(
            listOf(
                favorite("a", addedAtEpochMillis = reference),
                favorite("b", addedAtEpochMillis = reference),
            ),
        )
        assertContentEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun manualOrderIsLeftAlone() {
        val list = listOf(
            favorite("a", addedAtEpochMillis = reference),
            favorite("b", addedAtEpochMillis = reference + 60_000),
        )
        assertContentEquals(
            listOf("a", "b"),
            FavoritesOrdering.apply(FavoritesOrder.MANUAL, list).map { it.id },
        )
        assertContentEquals(
            listOf("b", "a"),
            FavoritesOrdering.apply(FavoritesOrder.RECENTLY_ADDED, list).map { it.id },
        )
    }

    @Test
    fun defaultOrderIsBrokenFirst() {
        assertEquals(FavoritesOrder.BROKEN_FIRST, FavoritesOrder.DEFAULT)
        // The former default stays selectable, next to the manual order.
        assertContentEquals(
            listOf(FavoritesOrder.BROKEN_FIRST, FavoritesOrder.RECENTLY_ADDED, FavoritesOrder.MANUAL),
            FavoritesOrder.entries,
        )
    }

    // Out of service on top, then unknown, then working — and within each
    // group the newest favorite first.
    @Test
    fun brokenFirstGroupsByStatusThenNewestFirst() {
        fun status(id: String, isWorking: Boolean?, minutes: Long) =
            favorite(id, addedAtEpochMillis = reference + minutes * 60_000).copy(isWorking = isWorking)
        val list = listOf(
            status("working-new", true, 50),
            status("broken-old", false, 10),
            status("unknown", null, 40),
            status("working-old", true, 20),
            status("broken-new", false, 30),
        )
        assertContentEquals(
            listOf("broken-new", "broken-old", "unknown", "working-new", "working-old"),
            FavoritesOrdering.apply(FavoritesOrder.BROKEN_FIRST, list).map { it.id },
        )
        // With every lift working it is simply newest first.
        val allWorking = list.map { it.copy(isWorking = true) }
        assertContentEquals(
            FavoritesOrdering.byRecentlyAdded(allWorking).map { it.id },
            FavoritesOrdering.byBrokenFirst(allWorking).map { it.id },
        )
    }

    // MARK: Adding

    @Test
    fun newFavoriteGoesToTheTopAndIsStamped() {
        val existing = listOf(favorite("a", addedAtEpochMillis = reference))
        val updated = FavoritesOrdering.inserting(favorite("b"), existing, atEpochMillis = reference + 60_000)
        assertContentEquals(listOf("b", "a"), updated.map { it.id })
        assertEquals(reference + 60_000, updated.first().addedAtEpochMillis)
    }

    @Test
    fun insertingKeepsAnExistingTimestamp() {
        val updated = FavoritesOrdering.inserting(
            favorite("b", addedAtEpochMillis = reference),
            emptyList(),
            atEpochMillis = reference + 60_000,
        )
        assertEquals(reference, updated.first().addedAtEpochMillis)
    }

    @Test
    fun insertingIgnoresDuplicates() {
        val existing = listOf(favorite("a", addedAtEpochMillis = reference))
        val updated = FavoritesOrdering.inserting(favorite("a"), existing, atEpochMillis = reference + 60_000)
        assertContentEquals(listOf("a"), updated.map { it.id })
        assertEquals(reference, updated.first().addedAtEpochMillis)
    }

    // MARK: Manual moves

    @Test
    fun moveReordersToTheTargetIndex() {
        val list = listOf(favorite("a"), favorite("b"), favorite("c"))
        assertContentEquals(
            listOf("c", "a", "b"),
            FavoritesOrdering.moved(list, fromIndex = 2, toIndex = 0).map { it.id },
        )
        assertContentEquals(
            listOf("b", "c", "a"),
            FavoritesOrdering.moved(list, fromIndex = 0, toIndex = 2).map { it.id },
        )
    }

    @Test
    fun moveIgnoresOutOfRangeIndices() {
        val list = listOf(favorite("a"), favorite("b"))
        assertContentEquals(list, FavoritesOrdering.moved(list, fromIndex = 0, toIndex = 2))
        assertContentEquals(list, FavoritesOrdering.moved(list, fromIndex = -1, toIndex = 1))
    }
}
