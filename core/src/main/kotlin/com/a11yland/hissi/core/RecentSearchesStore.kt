package com.a11yland.hissi.core

// Recent search terms, most-recent first. Case-insensitive dedup, capped at
// 10. Ported from `Shared/RecentSearchesStore.swift`; persistence is an
// injectable callback (the app wires DataStore) so the logic stays testable.
class RecentSearchesStore(
    initial: List<String> = emptyList(),
    private val persist: (List<String>) -> Unit = {},
) {
    var searches: List<String> = initial
        private set

    private val limit = 10

    fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val updated = searches.filterNot { it.equals(trimmed, ignoreCase = true) }
        searches = (listOf(trimmed) + updated).take(limit)
        persist(searches)
    }

    fun remove(query: String) {
        searches = searches.filterNot { it == query }
        persist(searches)
    }

    fun clear() {
        searches = emptyList()
        persist(searches)
    }
}
