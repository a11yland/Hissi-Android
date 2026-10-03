package com.a11yland.hissi.core

// The pure favorites-list operations behind the app's favorites store,
// ported from `Shared/FavoritesStore.swift` (persistence itself is DataStore,
// app-side). The iOS one-time legacy cleanup is deliberately not ported —
// this app never stored pre-migration ids.
object FavoritesOps {
    // Adding lives in FavoritesOrdering.inserting — a new favorite is
    // stamped with its add time and prepended, in both sort modes.
    fun removed(favorites: List<MonitoredElevator>, id: String): List<MonitoredElevator> =
        favorites.filterNot { it.id == id }

    // Rewrites favorites whose identity changed — a seed-id favorite resolved
    // to its live record through the inventory-number bridge adopts the live
    // (numeric) id on refresh. Only the listed favorites are touched, so a
    // toggle that raced the refresh survives. Deduplicated: the same elevator
    // favorited once via its seed id and once via its live id collapses into
    // one entry when the migration unifies the ids.
    fun migrated(
        favorites: List<MonitoredElevator>,
        replacements: Map<String, MonitoredElevator>,
    ): List<MonitoredElevator> {
        if (replacements.isEmpty()) return favorites
        val seen = mutableSetOf<String>()
        return favorites.map { replacements[it.id] ?: it }.filter { seen.add(it.id) }
    }
}
