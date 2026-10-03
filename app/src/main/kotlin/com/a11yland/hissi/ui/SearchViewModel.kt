package com.a11yland.hissi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.a11yland.hissi.HissiApplication
import com.a11yland.hissi.R
import com.a11yland.hissi.core.AccessibilityCloudClient
import com.a11yland.hissi.core.EquipmentCatalog
import com.a11yland.hissi.core.MonitoredElevator
import com.a11yland.hissi.core.NearbyStations
import com.a11yland.hissi.core.RecentSearchesStore
import com.a11yland.hissi.core.RegionGroup
import com.a11yland.hissi.core.SearchGrouping
import com.a11yland.hissi.core.StatusMerge
import com.a11yland.hissi.data.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Android counterpart of SearchService: the two-phase station search.
// Phase 1 renders instantly from the persisted/cached catalog of any age (or
// the bundled seed on first launch); phase 2 awaits the shared rebuild and
// re-renders with live statuses. Stale results beat an error screen — the
// error only shows when there is nothing to show at all.
class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val _regions = MutableStateFlow<List<RegionGroup>>(emptyList())
    val regions: StateFlow<List<RegionGroup>> = _regions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorRes = MutableStateFlow<Int?>(null)
    val errorRes: StateFlow<Int?> = _errorRes.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    // Deferred: a write racing the initial DataStore read must wait for the
    // loaded store, not land in a throwaway default.
    private val recentsStore = viewModelScope.async {
        RecentSearchesStore(initial = container.recents.load()) { updated ->
            _recents.value = updated
            viewModelScope.launch { container.recents.save(updated) }
        }.also { _recents.value = it.searches }
    }

    fun addRecent(query: String) {
        viewModelScope.launch { recentsStore.await().add(query) }
    }

    fun removeRecent(query: String) {
        viewModelScope.launch { recentsStore.await().remove(query) }
    }

    // The favorites screen's "Stationen in der Nähe anzeigen" entry: the app
    // focuses the search field (which shows the nearby section) and raises
    // this flag; the section consumes it and starts locating without a
    // second tap — the phone's showNearby(). A flag rather than an event so
    // the section may be composed only after the request was made.
    private val _nearbyRequested = MutableStateFlow(false)
    val nearbyRequested: StateFlow<Boolean> = _nearbyRequested.asStateFlow()

    fun requestNearby() {
        _nearbyRequested.value = true
    }

    fun consumeNearbyRequest() {
        _nearbyRequested.value = false
    }

    // Live status for one search result before it is a favorite: one targeted
    // request — the same path a favorites refresh takes, free while the
    // catalog cache is fresh — instead of waiting for the search's full
    // rebuild, which the pushed detail would not even see. The result is
    // folded back into the row behind the detail (SearchGrouping.replacing),
    // so list and detail never disagree. Null when the request didn't answer
    // for the id; the caller keeps what it has.
    suspend fun refreshed(elevator: MonitoredElevator): MonitoredElevator? {
        val record = runCatching { container.catalog().equipment(setOf(elevator.id)) }
            .getOrNull()?.get(elevator.id) ?: return null
        val merged = StatusMerge.apply(record, elevator)
        _regions.value = SearchGrouping.replacing(_regions.value, id = elevator.id, elevator = merged)
        return merged
    }

    // On-device distance sort over the catalog snapshot (seed-backfilled
    // coordinates); the seed carries the sort when no snapshot exists yet.
    suspend fun nearbyStations(latitude: Double, longitude: Double): List<NearbyStations.Station> {
        val seed = container.seed()
        val snapshot = container.catalog().snapshot()
        return withContext(Dispatchers.Default) {
            val catalog = snapshot ?: EquipmentCatalog.overlaid(live = emptyList(), seed = seed)
            NearbyStations.nearest(latitude, longitude, catalog)
        }
    }

    // Phase 2 of the nearby suggestions: one targeted request over exactly the
    // shown stations' elevators (free while the catalog cache is fresh),
    // folded back via restated — only statuses change, never names, distances
    // or order.
    suspend fun restatedNearby(
        stations: List<NearbyStations.Station>,
    ): List<NearbyStations.Station> {
        val fresh = container.catalog().equipment(NearbyStations.elevatorIds(stations))
        return NearbyStations.restated(stations, fresh)
    }

    // Called debounced on every query change; the caller cancels the previous
    // invocation, which must not write its superseded result over the newer
    // one's state (cancellation aborts between suspension points).
    suspend fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _regions.value = emptyList()
            _hasSearched.value = false
            _errorRes.value = null
            return
        }

        _isLoading.value = true
        _errorRes.value = null
        _hasSearched.value = true
        try {
            val catalog = container.catalog()

            // Phase 1, instant: the snapshot may be stale (a cold build takes
            // 30–60 s of pure server time) — names are stable, statuses
            // correct themselves in phase 2. On the very first launch the
            // bundled seed previews names (status unknown) instead of a bare
            // spinner; preview results are favoritable and bridge to their
            // live records on the next refresh.
            val snapshot = catalog.snapshot()
            if (snapshot != null) {
                _regions.value = grouped(trimmed, snapshot)
            } else {
                val seedRecords = container.seed()
                val seed = withContext(Dispatchers.Default) {
                    EquipmentCatalog.overlaid(live = emptyList(), seed = seedRecords)
                }
                if (seed.isNotEmpty()) {
                    _regions.value = grouped(trimmed, seed)
                }
            }

            // Phase 2, fresh: awaits the shared rebuild when the snapshot was
            // stale, then re-renders with live statuses.
            val all = catalog.all()
            if (all == null) {
                if (_regions.value.isEmpty()) _errorRes.value = R.string.search_failed
                return
            }
            _regions.value = grouped(trimmed, all)
        } finally {
            _isLoading.value = false
        }
    }

    // Matching + grouping walk the full catalog (~2550 records) — off the
    // main thread, it runs once per debounced keystroke.
    private suspend fun grouped(
        query: String,
        catalog: List<AccessibilityCloudClient.Equipment>,
    ): List<RegionGroup> = withContext(Dispatchers.Default) {
        SearchGrouping.group(AccessibilityCloudClient.matchStations(query, catalog))
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as HissiApplication
                return SearchViewModel(app.container) as T
            }
        }
    }
}
