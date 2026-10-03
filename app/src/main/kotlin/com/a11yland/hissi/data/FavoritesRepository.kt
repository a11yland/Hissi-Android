package com.a11yland.hissi.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.a11yland.hissi.core.FavoritesOps
import com.a11yland.hissi.core.FavoritesOrder
import com.a11yland.hissi.core.FavoritesOrdering
import com.a11yland.hissi.core.MonitoredElevator
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

// User-selected elevators to monitor — the Android counterpart of the iOS
// FavoritesStore, persisted via Preferences DataStore. Starts empty; users
// add elevators via search. Unlike iOS the persisted records double as the
// status cache: the app and the widget run in one process, so there is no
// separate App-Group mirror. The list operations are the fixture-tested
// FavoritesOps in :core.
class FavoritesRepository(private val context: Context) {
    private val key = stringPreferencesKey("favoriteElevators")
    private val orderKey = stringPreferencesKey("favoritesOrder")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): List<MonitoredElevator> {
        val stored = context.favoritesDataStore.data.first()[key] ?: return emptyList()
        return runCatching { json.decodeFromString<List<MonitoredElevator>>(stored) }
            .getOrDefault(emptyList())
    }

    // How the list is sorted. The stored list is always in the order the
    // user sees, so readers need none of this — only writers do.
    suspend fun loadOrder(): FavoritesOrder =
        context.favoritesDataStore.data.first()[orderKey]
            ?.let { stored -> FavoritesOrder.entries.firstOrNull { it.name == stored } }
            ?: FavoritesOrder.DEFAULT

    // Switching the mode re-sorts what is stored, so the list stays the
    // displayed order for every write that follows.
    suspend fun setOrder(order: FavoritesOrder): List<MonitoredElevator> =
        update(order = order) { FavoritesOrdering.apply(order, it) }

    // The user dragged rows into place: store that order verbatim and stop
    // re-deriving it, otherwise the next write would undo the drag.
    suspend fun setManualOrder(elevators: List<MonitoredElevator>): List<MonitoredElevator> =
        update(order = FavoritesOrder.MANUAL) { elevators }

    // Newest first: a new favorite goes to the top of the list in both
    // modes, stamped with the time it was added.
    suspend fun add(elevator: MonitoredElevator): List<MonitoredElevator> =
        update { FavoritesOrdering.inserting(elevator, it, System.currentTimeMillis()) }

    suspend fun remove(id: String): List<MonitoredElevator> =
        update { FavoritesOps.removed(it, id) }

    // Folds a refresh result back into the store in one transaction: rewrite
    // bridged identities (only the listed ones — a toggle that raced the
    // refresh survives), then update the stored records' statuses by id. The
    // stored set stays authoritative for membership.
    suspend fun applyRefresh(
        refreshed: List<MonitoredElevator>,
        migrations: Map<String, MonitoredElevator>,
    ): List<MonitoredElevator> = update { stored ->
        val byId = refreshed.associateBy { it.id }
        FavoritesOps.migrated(stored, migrations).map { favorite ->
            // The favorite stays authoritative for placement — the refresh
            // supplies the status, not the position or the add time.
            byId[favorite.id]?.copy(
                elevatorIndex = favorite.elevatorIndex,
                addedAtEpochMillis = favorite.addedAtEpochMillis,
            ) ?: favorite
        }
    }

    private suspend fun update(
        order: FavoritesOrder? = null,
        transform: (List<MonitoredElevator>) -> List<MonitoredElevator>,
    ): List<MonitoredElevator> {
        var result: List<MonitoredElevator> = emptyList()
        context.favoritesDataStore.edit { preferences ->
            val stored = preferences[key]?.let { value ->
                runCatching { json.decodeFromString<List<MonitoredElevator>>(value) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            result = transform(stored)
            preferences[key] = json.encodeToString(result)
            if (order != null) preferences[orderKey] = order.name
        }
        return result
    }
}
