package com.a11yland.hissi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a11yland.hissi.R
import com.a11yland.hissi.core.ElevatorStatus
import com.a11yland.hissi.core.MonitoredElevator

// Detail view of one elevator, the Android counterpart of the iOS
// ElevatorDetailView: status header, labeled fields, favorite toggle in the
// top bar, and — with coordinates — the map snippet (lite mode, tap hands the
// location to the user's maps app via a geo: intent). Landscape mirrors the
// iOS split: fields and map side by side.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElevatorDetailScreen(
    elevator: MonitoredElevator,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hasCoordinates = elevator.latitude != null && elevator.longitude != null
    // Street View coverage at the station, reported by the snippet's probe.
    // False keeps the snippet at one invisible pixel — still composed and
    // laid out, which the panorama needs to initialize and answer the probe
    // (at zero size it never does) — so the layout never shows an empty
    // frame, like the iOS row that only appears once a Look Around scene
    // loaded.
    var streetViewCovered by remember { mutableStateOf(false) }
    val streetViewHeight by animateDpAsState(
        if (streetViewCovered) 160.dp else 1.dp,
        label = "streetViewReveal",
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(elevator.stationName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    FavoriteStar(isFavorite = isFavorite, onToggle = onToggleFavorite)
                },
            )
        },
    ) { padding ->
        if (landscape && hasCoordinates) {
            Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailFields(elevator)
                }
                // Where Street View has coverage the column splits in half,
                // like the iOS map column — the map keeps the context, the
                // street-level view shows what the elevator looks like.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    MapSnippet(
                        latitude = elevator.latitude!!,
                        longitude = elevator.longitude!!,
                        stationName = elevator.stationName,
                        onOpen = { openInMaps(context, elevator) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    StreetViewSnippet(
                        latitude = elevator.latitude!!,
                        longitude = elevator.longitude!!,
                        stationName = elevator.stationName,
                        onCoverage = { streetViewCovered = it },
                        modifier = if (streetViewCovered) {
                            Modifier.weight(1f).fillMaxWidth()
                        } else {
                            Modifier.height(1.dp)
                        },
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailFields(elevator)
                if (hasCoordinates) {
                    MapSnippet(
                        latitude = elevator.latitude!!,
                        longitude = elevator.longitude!!,
                        stationName = elevator.stationName,
                        onOpen = { openInMaps(context, elevator) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    StreetViewSnippet(
                        latitude = elevator.latitude!!,
                        longitude = elevator.longitude!!,
                        stationName = elevator.stationName,
                        onCoverage = { streetViewCovered = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(streetViewHeight)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailFields(elevator: MonitoredElevator) {
    val status = ElevatorStatus.from(elevator.isWorking)
    val color = statusColor(status)

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Icon(status.icon.vector(), contentDescription = null, tint = statusSymbolColor(status))
        Text(
            statusLabel(status),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }

    if (elevator.isDataStale) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = staleWarningColor())
            Text(
                stringResource(R.string.status_stale),
                style = MaterialTheme.typography.bodySmall,
                color = staleWarningColor(),
            )
        }
    }

    HorizontalDivider()

    if (elevator.elevatorDescription.isNotEmpty()) {
        LabeledField(stringResource(R.string.field_elevator), elevator.elevatorDescription)
    }
    LabeledField(
        stringResource(R.string.field_data_age),
        elevator.lastUpdated?.let { relativeTimeText(it) }
            ?: stringResource(R.string.value_unknown),
    )
    elevator.stateExplanation?.takeIf { it.isNotEmpty() }?.let {
        LabeledField(stringResource(R.string.field_reason), it)
    }
    if (elevator.sourceName.isNotEmpty()) {
        LabeledField(stringResource(R.string.field_source), elevator.sourceName)
    }
    if (elevator.organizationName.isNotEmpty()) {
        LabeledField(stringResource(R.string.field_operator), elevator.organizationName)
    }
}

private fun openInMaps(context: Context, elevator: MonitoredElevator) {
    val uri = Uri.parse(
        "geo:${elevator.latitude},${elevator.longitude}" +
            "?q=${elevator.latitude},${elevator.longitude}" +
            "(${Uri.encode(elevator.stationName)})",
    )
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun LabeledField(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}
