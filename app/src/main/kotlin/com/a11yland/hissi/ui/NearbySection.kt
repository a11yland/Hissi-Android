package com.a11yland.hissi.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.a11yland.hissi.R
import com.a11yland.hissi.core.ElevatorStatus
import com.a11yland.hissi.core.NearbyStations
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

// "In der Nähe" block on the recents screen, mirroring the iOS
// NearbyStationsSection: a priming button until the user opts in, then the
// stations within walking distance (on-device sort over the catalog
// snapshot) as tappable rows that run the station search. One-shot fix, used
// in memory only — never stored; the permission prompt fires only from the
// button tap. The header names the radius so an empty list reads as
// "nothing within reach" rather than as a failure.
//
// Statuses land in two phases, like the search: the snapshot renders
// instantly and may be stale or status-less (first launch previews from the
// seed), then one targeted request over exactly the shown stations' elevators
// re-states the rows. A failed refresh keeps what is on screen — nearby is a
// suggestion, not a core flow.
@Composable
fun NearbySection(search: SearchViewModel, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<NearbyState>(NearbyState.Idle) }
    var stations by remember { mutableStateOf<List<NearbyStations.Station>>(emptyList()) }
    // Phase 1 has landed for the current fix — before that an empty list is
    // "not loaded yet", not "nothing within reach".
    var hasLoaded by remember { mutableStateOf(false) }
    var isRefreshingStatus by remember { mutableStateOf(false) }
    // "1 km", formatted like the row distances.
    val radiusLabel = remember { distanceLabel(NearbyStations.WALKING_RADIUS_METERS) }

    // Fine and coarse together: Android 12+ ignores a fine-only request
    // outright (no dialog, instant denial). An "approximate" choice grants
    // coarse only — plenty for a nearest-station sort.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        state = if (grants.values.any { it }) NearbyState.Locating else NearbyState.Denied
    }

    // The button's job, also run for the favorites screen's entry: locate if
    // allowed, else ask — the prompt only ever fires from a tap.
    val requestLocation = {
        if (hasLocationPermission(context)) {
            state = NearbyState.Locating
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // "Stationen in der Nähe anzeigen" tapped on the favorites screen: the
    // search field is focused (which composes this section) and the request
    // flag is up — start right away instead of showing the button again.
    val nearbyRequested by search.nearbyRequested.collectAsState()
    LaunchedEffect(nearbyRequested) {
        if (!nearbyRequested) return@LaunchedEffect
        search.consumeNearbyRequest()
        if (state == NearbyState.Idle || state == NearbyState.Denied) requestLocation()
    }

    // Permission already granted in an earlier session: no button ceremony,
    // locate straight away. On resume, so a grant made over in the system
    // settings takes effect on return, not on the next visit.
    LifecycleResumeEffect(Unit) {
        if (state != NearbyState.Locating && state != NearbyState.Located &&
            hasLocationPermission(context)
        ) {
            state = NearbyState.Locating
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(state) {
        when (state) {
            NearbyState.Locating -> {
                hasLoaded = false
                val fix = withTimeoutOrNull(15_000) { currentLocation(context) }
                state = if (fix == null) {
                    // Fall back to the button rather than an error screen —
                    // nearby is a suggestion, not a core flow.
                    NearbyState.Idle
                } else {
                    stations = search.nearbyStations(fix.first, fix.second)
                    hasLoaded = true
                    NearbyState.Located
                }
            }
            // Phase 2, fresh: setting Located above restarts this effect, so
            // the rows are already on screen while the request is out. What
            // it doesn't answer keeps its snapshot status.
            NearbyState.Located -> {
                if (stations.isEmpty()) return@LaunchedEffect
                isRefreshingStatus = true
                try {
                    runCatching { search.restatedNearby(stations) }
                        .getOrNull()?.let { stations = it }
                } finally {
                    isRefreshingStatus = false
                }
            }
            else -> {}
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                stringResource(R.string.nearby_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.nearby_radius, radiusLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The shown statuses are the snapshot's and the refresh is still
            // out — without this a stale row would look final.
            if (isRefreshingStatus) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.updating_status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (state) {
            NearbyState.Idle -> OutlinedButton(onClick = requestLocation) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Text(
                    stringResource(R.string.nearby_show),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            NearbyState.Locating -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.nearby_locating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NearbyState.Denied -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.nearby_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.nearby_open_settings),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
            }

            NearbyState.Located -> Column {
                if (hasLoaded && stations.isEmpty()) {
                    Text(
                        stringResource(R.string.nearby_none_within, radiusLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                stations.forEachIndexed { index, station ->
                    NearbyStationRow(station, onSelect = { onSelect(station.name) })
                    if (index < stations.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

// One suggestion: station, its lifts' verdict, distance. Sorted by distance
// only — the status is what the row says, never where it sits, so a landing
// refresh can't reshuffle the list under the user's finger.
@Composable
private fun NearbyStationRow(station: NearbyStations.Station, onSelect: () -> Unit) {
    val color = statusColor(station.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Tram,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                station.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Shape carries the status, colour only reinforces it.
                Icon(
                    station.status.icon.vector(),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stationStatusText(station),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    maxLines = 1,
                )
            }
        }
        Text(
            distanceLabel(station.distanceMeters),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// What the row says next to the symbol — Station.statusLabel from :core,
// rebuilt over string resources so it follows the app language (the :core
// label is the German test vocabulary). A station is not an elevator: the
// ratio is the useful part ("1 von 3" leaves an alternative, "1 von 1" does
// not), so the counts appear as soon as there is more than one lift. Never
// claims an all-clear while something is unknown.
@Composable
private fun stationStatusText(station: NearbyStations.Station): String = when {
    station.total <= 1 -> statusLabel(station.status)
    station.brokenCount > 0 ->
        stringResource(R.string.nearby_broken_ratio, station.brokenCount, station.total)
    station.unknownCount == station.total -> statusLabel(ElevatorStatus.Unknown)
    station.unknownCount > 0 ->
        stringResource(R.string.nearby_unknown_ratio, station.unknownCount, station.total)
    else -> stringResource(R.string.all_working)
}

private enum class NearbyState { Idle, Locating, Denied, Located }

// "350 m" / "1,2 km" / "1 km", locale-formatted; whole kilometres drop the
// decimal so the radius reads as "1 km", not "1,0 km".
private fun distanceLabel(meters: Double): String =
    when {
        meters < 1000 -> "${meters.toInt()} m"
        meters % 1000 == 0.0 -> "${(meters / 1000).toInt()} km"
        else -> String.format(Locale.getDefault(), "%.1f km", meters / 1000)
    }
