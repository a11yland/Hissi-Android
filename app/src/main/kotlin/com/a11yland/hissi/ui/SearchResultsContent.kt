package com.a11yland.hissi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.a11yland.hissi.R
import com.a11yland.hissi.core.SearchHighlight
import com.a11yland.hissi.core.TransitNetwork

// Grouped search results: Berlin/Brandenburg headers (only when both are
// present), stations, network subheaders inside stations that span several
// networks (S+U), and an inline favorite toggle per elevator — the Android
// counterpart of the iOS SearchResultsList.
@Composable
fun SearchResultsContent(
    search: SearchViewModel,
    monitor: MonitorViewModel,
    query: String,
    onOpen: (com.a11yland.hissi.core.MonitoredElevator) -> Unit,
) {
    val regions by search.regions.collectAsState()
    val isLoading by search.isLoading.collectAsState()
    val errorRes by search.errorRes.collectAsState()
    val hasSearched by search.hasSearched.collectAsState()
    val favorites by monitor.favorites.collectAsState()
    val favoriteIds = favorites.map { it.id }.toSet()

    when {
        isLoading && regions.isEmpty() -> Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        errorRes != null -> CenteredMessage(
            title = stringResource(R.string.error_title),
            detail = stringResource(errorRes!!),
            // Announce the failure — it replaces the results silently.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        regions.isEmpty() && hasSearched -> CenteredMessage(
            title = stringResource(R.string.no_results, query.trim()),
            detail = null,
        )

        regions.isEmpty() -> Unit

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Results are visible but phase 2 (live statuses) is still on its
            // way — without this, a stale snapshot would look final.
            if (isLoading) {
                item { RefreshingNotice() }
            }
            for (regionGroup in regions) {
                if (regions.size > 1) {
                    item(key = "region-${regionGroup.id}") {
                        Text(
                            regionGroup.region.label,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                }
                for (group in regionGroup.stations) {
                    item(key = "station-${regionGroup.id}-${group.id}") {
                        StationHeader(
                            name = group.station.name,
                            networks = group.networks,
                            query = query,
                        )
                    }
                    for (section in group.sections) {
                        if (group.sections.size > 1) {
                            item(key = "network-${regionGroup.id}-${group.id}-${section.id}") {
                                NetworkSubheader(network = section.network)
                            }
                        }
                        items(
                            count = section.elevators.size,
                            key = { "row-${regionGroup.id}-${group.id}-${section.id}-${section.elevators[it].id}" },
                        ) { index ->
                            val elevator = section.elevators[index]
                            ElevatorRow(
                                elevator = elevator,
                                modifier = Modifier.clickable { onOpen(elevator) },
                            ) {
                                FavoriteStar(isFavorite = elevator.id in favoriteIds) {
                                    monitor.toggleFavorite(elevator)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Subtle banner while the shown results are the instant (possibly stale)
// phase and the live refresh is still pending.
@Composable
private fun RefreshingNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.updating_status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredMessage(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Station header: network badges plus the name with the query match bolded —
// the "why is this a result" cue. The badge glyphs ("U"/"S"/"R") are
// unspeakable on their own; the whole header is one heading element that
// spells the networks out for TalkBack instead (mirrors the iOS
// accessibilityLabel).
@Composable
private fun StationHeader(name: String, networks: List<TransitNetwork>, query: String) {
    val spoken = networks.mapNotNull { spokenNetworkName(it) }
    val accessibleLabel =
        if (spoken.isEmpty()) name else "${spoken.joinToString(", ")}, $name"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = accessibleLabel
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (network in networks) {
            NetworkBadge(network)
        }
        val highlighted = buildAnnotatedString {
            append(name)
            SearchHighlight.range(name, query)?.let { range ->
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
            }
        }
        Text(highlighted, style = MaterialTheme.typography.titleMedium)
    }
}

// Subheader row separating the networks inside an S+U station.
@Composable
private fun NetworkSubheader(network: TransitNetwork) {
    val spoken = spokenNetworkName(network) ?: networkLabel(network)
    Row(
        modifier = Modifier
            .padding(start = 16.dp, top = 8.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = spoken
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkBadge(network)
        Text(
            networkLabel(network),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NetworkBadge(network: TransitNetwork) {
    val badge = network.badge ?: return
    Text(
        badge,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(network.badgeColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
            // Decorative — the enclosing header speaks the network names.
            .clearAndSetSemantics { },
    )
}
