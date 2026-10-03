package com.a11yland.hissi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.a11yland.hissi.HissiApplication
import com.a11yland.hissi.R
import com.a11yland.hissi.core.FavoritesOrder
import com.a11yland.hissi.core.FavoritesOrdering
import com.a11yland.hissi.core.MonitoredElevator
import com.a11yland.hissi.core.WelcomeGate
import com.a11yland.hissi.data.AppContainer
import com.a11yland.hissi.data.FavoritesRefresher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The Android counterpart of ElevatorMonitorService: favorites state plus the
// refresh orchestration. All list/merge logic is the fixture-tested :core.
class MonitorViewModel(private val container: AppContainer) : ViewModel() {
    private val _favorites = MutableStateFlow<List<MonitoredElevator>>(emptyList())
    val favorites: StateFlow<List<MonitoredElevator>> = _favorites.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // A string resource id, so the message follows the app language.
    private val _errorRes = MutableStateFlow<Int?>(null)
    val errorRes: StateFlow<Int?> = _errorRes.asStateFlow()

    private val refreshMutex = Mutex()

    // How the list is sorted. The stored list is always the displayed order,
    // so this only steers writes.
    private val _order = MutableStateFlow(FavoritesOrder.DEFAULT)
    val order: StateFlow<FavoritesOrder> = _order.asStateFlow()

    // Whether the drag in flight actually moved a row — a drop back onto the
    // starting position must not flip the list into manual mode.
    private var movedSinceDragStart = false

    // Welcome on first launch, "Was ist neu" once per curated release.
    private val _pendingOnboarding = MutableStateFlow<WelcomeGate.Sheet?>(null)
    val pendingOnboarding: StateFlow<WelcomeGate.Sheet?> = _pendingOnboarding.asStateFlow()

    // The disruption-alerts opt-in; persistence and worker scheduling live in
    // AlertsRepository, this is the UI's view of it.
    private val _alertsEnabled = MutableStateFlow(false)
    val alertsEnabled: StateFlow<Boolean> = _alertsEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            _favorites.value = container.favorites.load()
            _order.value = container.favorites.loadOrder()
            _pendingOnboarding.value = container.onboarding.pendingSheet()
            _alertsEnabled.value = container.alerts.isEnabled()
        }
    }

    fun setAlertsEnabled(enabled: Boolean) {
        if (enabled == _alertsEnabled.value) return
        _alertsEnabled.value = enabled
        viewModelScope.launch { container.alerts.setEnabled(enabled) }
    }

    // Switching the mode re-sorts the stored list, so every write that
    // follows keeps the displayed order.
    fun setOrder(order: FavoritesOrder) {
        if (order == _order.value) return
        _order.value = order
        viewModelScope.launch { _favorites.value = container.favorites.setOrder(order) }
    }

    // A drag in flight reorders the in-memory list only — the rows follow
    // the finger; the store learns the result once, on drop.
    fun move(fromIndex: Int, toIndex: Int) {
        val reordered = FavoritesOrdering.moved(_favorites.value, fromIndex, toIndex)
        if (reordered == _favorites.value) return
        _favorites.value = reordered
        movedSinceDragStart = true
    }

    // A drag is what puts the list into manual mode — re-deriving the order
    // afterwards would undo it.
    fun commitManualOrder() {
        if (!movedSinceDragStart) return
        movedSinceDragStart = false
        _order.value = FavoritesOrder.MANUAL
        viewModelScope.launch { container.favorites.setManualOrder(_favorites.value) }
    }

    fun dismissOnboarding() {
        val sheet = _pendingOnboarding.value ?: return
        _pendingOnboarding.value = null
        viewModelScope.launch {
            when (sheet) {
                is WelcomeGate.Sheet.Welcome -> container.onboarding.markWelcomeSeen()
                is WelcomeGate.Sheet.WhatsNew -> container.onboarding.markWhatsNewSeen(sheet.version)
            }
        }
    }

    // Refreshes all favorites in one catalog batch (FavoritesRefresher — the
    // path the widget reload shares); a bridged seed-id favorite comes back
    // under its live identity and the id migration is persisted. Failures
    // keep previous values and surface one error line. The widget re-renders
    // afterwards either way — its data is the store this refresh just wrote.
    suspend fun refresh() {
        refreshMutex.withLock {
            _isLoading.value = true
            _errorRes.value = null
            try {
                val result = FavoritesRefresher.refreshAll(container)
                _favorites.value = result.elevators
                if (result.failedStations.isNotEmpty()) {
                    _errorRes.value = R.string.refresh_failed
                }
                container.reloadWidgets()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    fun toggleFavorite(elevator: MonitoredElevator) {
        viewModelScope.launch {
            if (isFavorite(elevator.id)) {
                _favorites.value = container.favorites.remove(elevator.id)
                container.reloadWidgets()
            } else {
                _favorites.value = container.favorites.add(elevator)
                container.reloadWidgets()
                // Search results can carry no status (e.g. a superseded
                // catalog build) — fetch it now instead of waiting for the
                // next cycle.
                if (elevator.isWorking == null) refresh()
            }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as HissiApplication
                return MonitorViewModel(app.container) as T
            }
        }
    }
}
