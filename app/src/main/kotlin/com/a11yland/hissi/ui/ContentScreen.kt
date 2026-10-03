package com.a11yland.hissi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.a11yland.hissi.R
import com.a11yland.hissi.core.FavoritesOrder
import com.a11yland.hissi.core.MonitoredElevator
import com.a11yland.hissi.core.RefreshInterval
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// The single screen, mirroring the iOS ContentView: a persistent bottom
// search field, above which the area shows the favorites by default, the
// recent searches while the field is focused and empty, or the grouped
// search results while a query is present.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentScreen(
    isDark: Boolean,
    onToggleAppearance: () -> Unit,
    // Incremented by the activity for every launch intent that asks for the
    // nearby block (the nearby widget's tap); 0 means none.
    nearbyRequests: Int = 0,
    monitor: MonitorViewModel = viewModel(factory = MonitorViewModel.Factory),
    search: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
) {
    // Saveable: the mode derives from the query, so losing it on rotation
    // would dump the user out of their search results and back to favorites.
    var query by rememberSaveable { mutableStateOf("") }
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocus = remember { FocusRequester() }
    // The favorites screen's way to the nearby suggestions, like the iOS
    // showNearby(): present the search (focus = recents mode, which holds the
    // nearby section) and have the section locate without a second tap.
    val showNearby: () -> Unit = {
        search.requestNearby()
        searchFocus.requestFocus()
    }
    LaunchedEffect(nearbyRequests) {
        if (nearbyRequests > 0) showNearby()
    }
    val searchFocused by searchInteraction.collectIsFocusedAsState()
    val favorites by monitor.favorites.collectAsState()
    val isRefreshing by monitor.isLoading.collectAsState()
    val pendingOnboarding by monitor.pendingOnboarding.collectAsState()
    val scope = rememberCoroutineScope()

    // The pushed detail view (state-based — one screen plus an overlay
    // mirrors the iOS single-stack push closely enough without a nav graph).
    // Saveable so the pushed screen survives rotation and process death; the
    // snapshot rides along as JSON (it may be a search result, not a
    // favorite, so an id alone could not restore it).
    var selected by rememberSaveable(stateSaver = monitoredElevatorSaver) {
        mutableStateOf<MonitoredElevator?>(null)
    }

    val mode = when {
        query.isNotBlank() -> Mode.Results
        searchFocused -> Mode.Recents
        else -> Mode.Favorites
    }

    // Refresh on (re)entering the foreground, then every 30 minutes while it
    // stays there — the Android counterpart of the iOS on-appear +
    // scenePhase-active + Task.sleep loop.
    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            monitor.refresh()
            while (true) {
                delay(RefreshInterval.interval)
                monitor.refresh()
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    // Debounced two-phase search; a keystroke cancels the previous search
    // (and with it any state writes it still had pending).
    LaunchedEffect(query) {
        delay(250)
        search.search(query)
    }

    // Selection feedback on favorite add/remove (the count only changes
    // then), error feedback when a refresh fails — routine polls stay silent.
    // Mirrors the iOS sensoryFeedback wiring.
    val haptics = LocalHapticFeedback.current
    val monitorErrorRes by monitor.errorRes.collectAsState()
    LaunchedEffect(Unit) {
        snapshotFlow { favorites.size }
            .drop(1)
            .collect { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    }
    LaunchedEffect(monitorErrorRes) {
        if (monitorErrorRes != null) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    selected?.let { pushed ->
        // A search result's own refresh: the pushed snapshot is only as fresh
        // as the catalog it came from, and that may be the seed with no
        // status at all. Favorites don't need it — the monitor refreshes them.
        var refreshed by remember(pushed.id) { mutableStateOf<MonitoredElevator?>(null) }
        LaunchedEffect(pushed.id) {
            if (favorites.none { it.id == pushed.id }) {
                refreshed = search.refreshed(pushed)
            }
        }
        // Favorites refresh while the view is open (e.g. right after
        // favoriting a status-less search result) — prefer the live copy
        // over the pushed snapshot, then the view's own refresh. A refreshed
        // seed record may carry the live id, so the favorite is looked up
        // under both.
        val live = favorites.firstOrNull { it.id == pushed.id || it.id == refreshed?.id }
            ?: refreshed
            ?: pushed
        BackHandler { selected = null }
        ElevatorDetailScreen(
            elevator = live,
            isFavorite = favorites.any { it.id == live.id },
            onToggleFavorite = { monitor.toggleFavorite(live) },
            onBack = { selected = null },
        )
        return
    }

    pendingOnboarding?.let { sheet ->
        OnboardingSheet(sheet = sheet, onDismiss = { monitor.dismissOnboarding() })
    }

    // "Über Hissi" behind the app bar logo, like the iOS toolbar logo.
    var showsAbout by rememberSaveable { mutableStateOf(false) }
    if (showsAbout) {
        AboutSheet(onDismiss = { showsAbout = false })
    }

    Scaffold(
        // Keeps the bottom search field above the keyboard instead of the
        // system panning the whole window (which would push the app bar off).
        modifier = Modifier.imePadding(),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = { showsAbout = true }) {
                        AppIcon(size = 28.dp, cornerRadius = 6.dp)
                    }
                },
                title = {
                    Text(
                        stringResource(
                            when (mode) {
                                Mode.Favorites -> R.string.title_favorites
                                Mode.Recents -> R.string.title_recents
                                Mode.Results -> R.string.title_search
                            },
                        ),
                    )
                },
                actions = {
                    if (mode == Mode.Favorites && favorites.size > 1) {
                        SortMenu(monitor)
                    }
                    if (mode == Mode.Favorites && favorites.isNotEmpty()) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = { scope.launch { monitor.refresh() } }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.action_refresh),
                                )
                            }
                        }
                    }
                    IconButton(onClick = onToggleAppearance) {
                        Icon(
                            if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = stringResource(
                                if (isDark) R.string.appearance_light else R.string.appearance_dark,
                            ),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    // Scaffold's bottomBar slot applies no insets of its own —
                    // with 3-button navigation the bar would cover the field.
                    // The ime padding on the Scaffold consumes what it applied,
                    // so this adds nothing extra while the keyboard is up.
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(searchFocus),
                    interactionSource = searchInteraction,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_prompt)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.clear_search),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search.addRecent(query) }),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (mode) {
                Mode.Favorites -> FavoritesContent(
                    monitor = monitor,
                    onOpen = { selected = it },
                    onShowNearby = showNearby,
                )
                Mode.Recents -> RecentsContent(
                    search = search,
                    onPick = { term ->
                        query = term
                        search.addRecent(term)
                    },
                )
                Mode.Results -> SearchResultsContent(
                    search = search,
                    monitor = monitor,
                    query = query,
                    onOpen = { selected = it },
                )
            }
        }
    }
}

// "Stationen in der Nähe anzeigen" — the same wording and glyph as the
// nearby section's own opt-in button, so the two read as one entry.
@Composable
private fun NearbyEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.LocationOn, contentDescription = null)
        Text(stringResource(R.string.nearby_show), modifier = Modifier.padding(start = 8.dp))
    }
}

private enum class Mode { Favorites, Recents, Results }

private val monitoredElevatorSaver = Saver<MonitoredElevator?, String>(
    save = { value -> value?.let { Json.encodeToString(it) } ?: "" },
    restore = { saved ->
        saved.takeIf { it.isNotEmpty() }?.let {
            runCatching { Json.decodeFromString<MonitoredElevator>(it) }.getOrNull()
        }
    },
)

// MARK: Favorites

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesContent(
    monitor: MonitorViewModel,
    onOpen: (MonitoredElevator) -> Unit,
    onShowNearby: () -> Unit,
) {
    val favorites by monitor.favorites.collectAsState()
    val isRefreshing by monitor.isLoading.collectAsState()
    val errorRes by monitor.errorRes.collectAsState()
    val scope = rememberCoroutineScope()

    if (favorites.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.no_favorites_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.no_favorites_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            NearbyEntryButton(onClick = onShowNearby, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }

    // Long-press and drag reorders right here — no edit mode, no setting to
    // pick first. The drag itself is what moves the list into "Eigene
    // Reihenfolge" (committed on drop, MonitorViewModel).
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        // Offset by the alerts section and the nearby entry, which sit at
        // LazyColumn indices 0 and 1.
        monitor.move(from.index - 2, to.index - 2)
    }
    val alertsEnabled by monitor.alertsEnabled.collectAsState()

    // A drag tells you where the row landed by looking at it; the move
    // actions below don't, so say it. 1-based, like the iOS announcement.
    // Spoken through a polite live region (announceForAccessibility is
    // deprecated since API 36): the node below sits outside the LazyColumn so
    // it is always composed, and TalkBack reads every change of its text.
    val context = LocalContext.current
    var moveAnnouncement by remember { mutableStateOf("") }
    val announceMove = { elevator: MonitoredElevator, position: Int, count: Int ->
        moveAnnouncement =
            context.getString(R.string.moved_announcement, elevator.stationName, position, count)
    }
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { scope.launch { monitor.refresh() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = moveAnnouncement
                },
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // Above the list like the iOS LiveStatusSection — the alerts
            // report on exactly these favorites.
            item(key = "alerts-section") {
                AlertsSection(enabled = alertsEnabled, onSetEnabled = monitor::setAlertsEnabled)
            }
            // Same entry as the empty state's button, above the favorites
            // (the list can run long, the entry must not sit under it).
            item(key = "nearby-entry") {
                NearbyEntryButton(
                    onClick = onShowNearby,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(favorites, key = { _, elevator -> elevator.id }) { index, elevator ->
                ReorderableItem(reorderableState, key = elevator.id) { isDragging ->
                    Surface(shadowElevation = if (isDragging) 4.dp else 0.dp) {
                        ElevatorRow(
                            elevator = elevator,
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    enabled = favorites.size > 1,
                                    onDragStopped = { monitor.commitManualOrder() },
                                )
                                .clickable { onOpen(elevator) }
                                // Move-by-one, offered to assistive
                                // technologies in place of the drag. Lands in
                                // the manual order the same way.
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) {
                                            add(
                                                CustomAccessibilityAction(moveUpLabel) {
                                                    monitor.move(index, index - 1)
                                                    monitor.commitManualOrder()
                                                    announceMove(elevator, index, favorites.size)
                                                    true
                                                },
                                            )
                                        }
                                        if (index < favorites.size - 1) {
                                            add(
                                                CustomAccessibilityAction(moveDownLabel) {
                                                    monitor.move(index, index + 1)
                                                    monitor.commitManualOrder()
                                                    announceMove(elevator, index + 2, favorites.size)
                                                    true
                                                },
                                            )
                                        }
                                    }
                                },
                        ) {
                            // The grip says the rows can be dragged — the
                            // gesture alone is invisible. Decorative: the
                            // drag starts anywhere on the row, and assistive
                            // technologies get the move actions.
                            if (favorites.size > 1) {
                                Icon(
                                    Icons.Filled.DragIndicator,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FavoriteStar(isFavorite = true) { monitor.toggleFavorite(elevator) }
                        }
                    }
                }
            }
            item {
                FavoritesFooter(
                    lastChecked = favorites.mapNotNull { it.lastChecked }.maxOrNull(),
                    errorRes = errorRes,
                )
            }
        }
    }
}

// Sorting sits on the top level, not behind an overflow menu: it belongs to
// the list below it. The menu is a state display — which order the list is
// in, and the way back to the default. Nothing in here has to be set before
// dragging a row.
@Composable
private fun SortMenu(monitor: MonitorViewModel) {
    val order by monitor.order.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FavoritesOrder.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(stringResource(candidate.labelRes)) },
                    leadingIcon = {
                        if (candidate == order) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        monitor.setOrder(candidate)
                    },
                )
            }
        }
    }
}

private val FavoritesOrder.labelRes: Int
    get() = when (this) {
        FavoritesOrder.RECENTLY_ADDED -> R.string.order_recently_added
        FavoritesOrder.MANUAL -> R.string.order_manual
    }

@Composable
private fun FavoritesFooter(lastChecked: java.time.Instant?, errorRes: Int?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (errorRes != null) {
            // Live region: the silent overlay would otherwise never reach
            // TalkBack users (mirrors the iOS accessibility announcement).
            Text(
                stringResource(errorRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // One shared poll covers all favorites, so "checked" is global; the
        // per-row line shows the source's data age.
        if (lastChecked != null) {
            Text(
                stringResource(R.string.checked_at, relativeTimeText(lastChecked)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AttributionFooter()
    }
}
