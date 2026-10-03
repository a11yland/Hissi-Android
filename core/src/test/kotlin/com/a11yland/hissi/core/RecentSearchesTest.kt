package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/RecentSearchesTests.swift; persistence
// is an in-memory holder standing in for UserDefaults/DataStore.
class RecentSearchesTest {
    private class Holder {
        var stored: List<String> = emptyList()
        fun store() = RecentSearchesStore(initial = stored, persist = { stored = it })
    }

    private fun makeStore() = Holder().store()

    @Test
    fun addPrependsMostRecent() {
        val store = makeStore()
        store.add("Alexanderplatz")
        store.add("Zoo")
        assertContentEquals(listOf("Zoo", "Alexanderplatz"), store.searches)
    }

    @Test
    fun dedupIsCaseInsensitiveAndMovesToFront() {
        val store = makeStore()
        store.add("Alex")
        store.add("Zoo")
        store.add("alex")
        // Single entry, moved to the front, keeping the newest casing.
        assertContentEquals(listOf("alex", "Zoo"), store.searches)
    }

    @Test
    fun trimsWhitespaceAndIgnoresBlank() {
        val store = makeStore()
        store.add("  Alex  ")
        store.add("   ")
        assertContentEquals(listOf("Alex"), store.searches)
    }

    @Test
    fun capsAtLimit() {
        val store = makeStore()
        for (i in 0 until 15) store.add("q$i")
        assertEquals(10, store.searches.size)
        assertEquals("q14", store.searches.first())
        assertEquals("q5", store.searches.last())
    }

    @Test
    fun removeAndClear() {
        val store = makeStore()
        store.add("A")
        store.add("B")
        store.remove("A")
        assertContentEquals(listOf("B"), store.searches)
        store.clear()
        assertTrue(store.searches.isEmpty())
    }

    @Test
    fun persistsAcrossInstances() {
        val holder = Holder()
        holder.store().add("Alexanderplatz")
        assertContentEquals(listOf("Alexanderplatz"), holder.store().searches)
    }
}
