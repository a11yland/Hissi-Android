package com.a11yland.hissi.data

import com.a11yland.hissi.core.ElevatorRefresher
import com.a11yland.hissi.core.MonitoredElevator
import com.a11yland.hissi.core.RefreshInterval
import com.a11yland.hissi.core.StatusSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The one shared refresh path behind the app's pull/poll, the widget reload
// and (phase 5) the alert worker — the Android counterpart of the iOS
// refresh-plus-RefreshFanOut pair, collapsed: app and widget share a process,
// so persisting into the favorites store IS the fan-out (the stored records
// double as the status cache, see FavoritesRepository).
object FavoritesRefresher {
    data class Result(
        val elevators: List<MonitoredElevator>,
        val failedStations: Set<String>,
    )

    // Refreshes all favorites in one catalog batch and persists the result: a
    // bridged seed-id favorite comes back under its live identity and the id
    // migration is written with it. Failures keep previous values.
    suspend fun refreshAll(container: AppContainer): Result {
        val current = container.favorites.load()
        if (current.isEmpty()) return Result(emptyList(), emptySet())
        val catalog = container.catalog()
        // Off the main thread: equipment() may join a stale snapshot against
        // the full seed catalog before the merge.
        val (updated, failed) = withContext(Dispatchers.Default) {
            ElevatorRefresher.refreshAll(
                current,
                sources = StatusSources { catalog.equipment(it) },
            )
        }
        val migrations = current.zip(updated)
            .filter { (old, new) -> old.id != new.id }
            .associate { (old, new) -> old.id to new }
        return Result(container.favorites.applyRefresh(updated, migrations), failed)
    }

    // Whether the stored statuses were fetched within the reuse window — a
    // widget reload landing right after the app's refresh (which is what
    // triggered it) answers from the store instead of fetching again.
    fun freshEnough(favorites: List<MonitoredElevator>): Boolean {
        val checked = favorites.mapNotNull { it.lastCheckedEpochMillis }.maxOrNull() ?: return false
        return System.currentTimeMillis() - checked < RefreshInterval.cacheReuse.inWholeMilliseconds
    }
}
