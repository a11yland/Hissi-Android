package com.a11yland.hissi.core

// How the favorites list is ordered. The stored order *is* the displayed
// order — every reader takes the favorites list as it stands, so ordering is
// applied once when writing and nowhere else. The mode only decides what
// happens on the next write: BROKEN_FIRST re-derives the order from the
// statuses (so every refresh is such a write), RECENTLY_ADDED from addedAt,
// MANUAL leaves the order the user dragged into place alone.
// Ported from `Shared/FavoritesOrdering.swift`.
enum class FavoritesOrder {
    // Default: what is out of service stands on top — the elevators one
    // opens the app for. Then unknown, then working; within each group the
    // newest favorite first.
    BROKEN_FIRST,

    // The former default: newest favorite first, whatever its status.
    RECENTLY_ADDED,

    // Set implicitly the moment the user drags a row — nobody has to pick it
    // first, and a manual order that a re-sort would silently undo is not
    // worth persisting. It labels the state the list is in, not an action.
    MANUAL,
    ;

    companion object {
        val DEFAULT = BROKEN_FIRST
    }
}

// Pure ordering rules, platform-free and unit-tested. Persistence and the
// UI live elsewhere (FavoritesRepository, ContentScreen).
object FavoritesOrdering {
    // Newest first. Stable: equal timestamps keep their relative position,
    // and favorites without one — stored before the timestamp existed — sort
    // after the timestamped ones rather than jumping to the top.
    fun byRecentlyAdded(favorites: List<MonitoredElevator>): List<MonitoredElevator> =
        // nullsFirst + descending = nulls last, newest first; sortedWith is
        // stable, so ties keep their stored order.
        favorites.sortedWith(
            compareByDescending(nullsFirst(naturalOrder<Long>())) { it.addedAtEpochMillis },
        )

    // Disruptions first, then unknown, then working (ElevatorRanking —
    // the same urgency order the widget cuts its rows by); within each group
    // newest first. Stable.
    fun byBrokenFirst(favorites: List<MonitoredElevator>): List<MonitoredElevator> =
        ElevatorRanking.byUrgency(byRecentlyAdded(favorites))

    // Applies `order` to a favorites list. MANUAL is the identity — the
    // stored order already is the user's order.
    fun apply(order: FavoritesOrder, favorites: List<MonitoredElevator>): List<MonitoredElevator> =
        when (order) {
            FavoritesOrder.BROKEN_FIRST -> byBrokenFirst(favorites)
            FavoritesOrder.RECENTLY_ADDED -> byRecentlyAdded(favorites)
            FavoritesOrder.MANUAL -> favorites
        }

    // A new favorite goes to the top: it is the most recent one, and in a
    // manually sorted list a fresh entry is easier to move from a visible
    // position than to find at the bottom. (In BROKEN_FIRST the store
    // applies the order afterwards, which puts it on top of its status
    // group.)
    fun inserting(
        elevator: MonitoredElevator,
        favorites: List<MonitoredElevator>,
        atEpochMillis: Long,
    ): List<MonitoredElevator> {
        if (favorites.any { it.id == elevator.id }) return favorites
        val stamped =
            if (elevator.addedAtEpochMillis != null) elevator
            else elevator.copy(addedAtEpochMillis = atEpochMillis)
        return listOf(stamped) + favorites
    }

    // Moves one row to a target position in the resulting list — the contract
    // Compose drag callbacks hand over, unlike the Swift original's
    // SwiftUI-specific IndexSet-plus-insertion-offset one.
    fun moved(favorites: List<MonitoredElevator>, fromIndex: Int, toIndex: Int): List<MonitoredElevator> {
        if (fromIndex == toIndex) return favorites
        if (fromIndex !in favorites.indices || toIndex !in favorites.indices) return favorites
        val result = favorites.toMutableList()
        result.add(toIndex, result.removeAt(fromIndex))
        return result
    }
}
